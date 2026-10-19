import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act, fireEvent } from '@testing-library/react';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import useStore from '../store/useStore';
import { setPendingBlobs, mediaUrl } from '../utils/media';
import { embedsExtension, htmlLinksToMarkdown } from './embeds';
import { renderMarkdown } from '../utils/markdown';
import CmEditor from './CmEditor';

global.URL.createObjectURL = vi.fn(() => 'blob:mock-url');
global.URL.revokeObjectURL = vi.fn();

function mk(doc, { editable = false, cursor, opts } = {}) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  return new EditorView({
    parent,
    state: EditorState.create({
      doc,
      selection: cursor != null ? { anchor: cursor } : undefined,
      extensions: [embedsExtension(opts), EditorView.editable.of(editable)],
    }),
  });
}

describe('embed widgets', () => {
  beforeEach(() => setPendingBlobs({}));

  it('renders by extension with mediaUrl src', () => {
    const v = mk('![[a b.png]]\n![[v.mp4]]\n![[s.mp3]]\n![[d.pdf]]\n![[f.zip]]');
    const q = (s) => v.dom.querySelector(s);
    expect(q('img.embedded-image').getAttribute('src')).toBe(mediaUrl('a b.png'));
    expect(q('video').getAttribute('src')).toBe('/vault-media/videos/v.mp4');
    expect(q('video').controls).toBe(true);
    expect(q('audio').getAttribute('src')).toBe('/vault-media/audio/s.mp3');
    expect(q('a[href*="/pdf/"]').textContent).toBe('📄 d.pdf');
    expect(q('a[href*="/files/"]').textContent).toBe('📎 f.zip');
  });

  it('shows raw text while cursor is on the line (edit mode)', () => {
    const v = mk('![[a.png]]\nx', { editable: true, cursor: 3 });
    expect(v.dom.querySelector('img.embedded-image')).toBeNull();
    act(() => v.dispatch({ selection: { anchor: 12 } }));
    expect(v.dom.querySelector('img.embedded-image')).not.toBeNull();
  });

  it('pending blob wins over vault URL, and updates on registry change', async () => {
    setPendingBlobs({ 'a.png': { blobURL: 'blob:x' } });
    const v = mk('![[a.png]]');
    expect(v.dom.querySelector('img.embedded-image').getAttribute('src')).toBe('blob:x');
    setPendingBlobs({});
    await act(async () => { await Promise.resolve(); });
    expect(v.dom.querySelector('img.embedded-image').getAttribute('src')).toBe(mediaUrl('a.png'));
  });

  it('keeps the same DOM node across unrelated edits (eq)', () => {
    const v = mk('![[v.mp4]]\ntext', { editable: false });
    const before = v.dom.querySelector('video');
    act(() => v.dispatch({ changes: { from: v.state.doc.length, insert: '!' } }));
    expect(v.dom.querySelector('video')).toBe(before);
  });

  it('read-only image click opens lightbox callback', () => {
    const onImageClick = vi.fn();
    const v = mk('![[a.png]]', { opts: { onImageClick } });
    fireEvent.click(v.dom.querySelector('img.embedded-image'));
    expect(onImageClick).toHaveBeenCalledWith(expect.stringContaining('a.png'), 'a.png');
  });

  it('registers atomic ranges', () => {
    const v = mk('![[a.png]]x', { editable: false });
    expect(v.state.facet(EditorView.atomicRanges).length).toBeGreaterThan(0);
  });
});

describe('htmlLinksToMarkdown', () => {
  it('converts anchors, skips fragments', () => {
    expect(htmlLinksToMarkdown('<p>see <a href="https://x.io">X</a> and <a href="#h">H</a></p>'))
      .toBe('see [X](https://x.io) and H');
  });
});

describe('CmEditor attachments (ported paste expectations)', () => {
  let showToast, addPendingFile;
  beforeEach(() => {
    showToast = vi.fn(); addPendingFile = vi.fn();
    setPendingBlobs({});
    useStore.setState({
      currentNotePath: '/v/A.md', isMutable: true, editorResetKey: 0, pendingFiles: {},
      pendingFrontmatter: '', pendingRaw: 'hello', showToast, addPendingFile,
    });
  });
  const view = (c) => EditorView.findFromDOM(c.querySelector('.cm-editor'));
  const pasteEv = (data) => {
    const e = new Event('paste', { bubbles: true, cancelable: true });
    e.clipboardData = { files: [], getData: () => '', ...data };
    return e;
  };

  it('pasted file: renamed, stored, blob registered, embed inserted', () => {
    const { container } = render(<CmEditor />);
    const file = new File(['x'], 'my photo.png', { type: 'image/png' });
    act(() => { view(container).contentDOM.dispatchEvent(pasteEv({ files: [file] })); });
    expect(addPendingFile).toHaveBeenCalledWith(expect.stringMatching(/^my_photo-\d+[0-9a-f]{8}\.png$/), file, 'blob:mock-url');
    expect(view(container).state.doc.toString()).toMatch(/!\[\[my_photo-\d+[0-9a-f]{8}\.png\]\]/);
  });

  it('unsupported file: toast, nothing inserted', () => {
    const { container } = render(<CmEditor />);
    const file = new File(['x'], 'a.txt', { type: 'text/plain' });
    act(() => { view(container).contentDOM.dispatchEvent(pasteEv({ files: [file] })); });
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('1 file skipped'));
    expect(addPendingFile).not.toHaveBeenCalled();
    expect(view(container).state.doc.toString()).toBe('hello');
  });

  it('HTML link paste becomes markdown; plain paste untouched', () => {
    const { container } = render(<CmEditor />);
    const e = pasteEv({ getData: (t) => (t === 'text/html' ? '<a href="https://x.io">X</a>' : '') });
    act(() => { view(container).contentDOM.dispatchEvent(e); });
    expect(e.defaultPrevented).toBe(true);
    expect(view(container).state.doc.toString()).toContain('[X](https://x.io)');
    const plain = pasteEv({ getData: (t) => (t === 'text/plain' ? 'p' : '') });
    act(() => { view(container).contentDOM.dispatchEvent(plain); });
    expect(view(container).state.doc.toString()).toBe('[X](https://x.io)phello');
  });

  it('picker inserts embed; drop overlay shows on dragover', () => {
    const { container, getByTestId } = render(<CmEditor />);
    const file = new File(['x'], 'a.mp4', { type: 'video/mp4' });
    fireEvent.change(getByTestId('cm-file-input'), { target: { files: [file] } });
    expect(view(container).state.doc.toString()).toMatch(/!\[\[a-\d+[0-9a-f]{8}\.mp4\]\]/);
    const over = new Event('dragover', { bubbles: true, cancelable: true });
    over.dataTransfer = { items: [{ kind: 'file' }] };
    act(() => { view(container).contentDOM.dispatchEvent(over); });
    expect(container.textContent).toContain('Drop to attach');
  });
});

describe('MarkdownView embed rule', () => {
  it('renders embeds by type with identical URLs', () => {
    const h = renderMarkdown('![[a.png]] ![[v.mp4]] ![[d.pdf]]');
    expect(h).toContain(`src="${mediaUrl('a.png')}"`);
    expect(h).toContain('<video');
    expect(h).toContain('📄 d.pdf');
  });
});
