import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { EditorView } from '@codemirror/view';
import useStore from '../store/useStore';
import CmEditor from './CmEditor';

describe('CmEditor', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useStore.setState({
      currentNotePath: '/v/A.md', isMutable: true, editorResetKey: 0,
      pendingFrontmatter: '---\na: 1\n---\n\n', pendingRaw: '---\na: 1\n---\n\n# Hi\nbody',
    });
  });
  afterEach(() => vi.useRealTimers());

  it('does not touch pendingRaw on load, writes body after debounce', () => {
    const { container } = render(<CmEditor />);
    const raw = useStore.getState().pendingRaw;
    act(() => { vi.advanceTimersByTime(500); });
    expect(useStore.getState().pendingRaw).toBe(raw);
    const view = EditorView.findFromDOM(container.querySelector('.cm-editor'));
    expect(view.state.doc.toString()).toBe('# Hi\nbody');
    act(() => { view.dispatch({ changes: { from: 9, insert: '!' } }); });
    act(() => { vi.advanceTimersByTime(300); });
    expect(useStore.getState().pendingRaw).toBe('---\na: 1\n---\n\n# Hi\nbody!');
  });

  it('shows empty state without a note', () => {
    useStore.setState({ currentNotePath: null });
    const { getByText } = render(<CmEditor />);
    expect(getByText('Select a note to get started')).toBeTruthy();
  });
});
