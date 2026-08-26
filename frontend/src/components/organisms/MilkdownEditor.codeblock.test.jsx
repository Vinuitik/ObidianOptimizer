import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import React from 'react';

// ── Mock heavy Milkdown dependencies ─────────────────────────────────────────

let capturedUsePlugins = [];

vi.mock('@milkdown/react', () => ({
  Milkdown: () => <div data-testid="milkdown" />,
  MilkdownProvider: ({ children }) => <>{children}</>,
  useEditor: vi.fn(factory => factory(document.createElement('div'))),
  useInstance: vi.fn(() => [false, vi.fn()]),
}));
vi.mock('@milkdown/preset-commonmark', () => ({ commonmark: { _id: 'commonmark' } }));
vi.mock('@milkdown/preset-gfm',        () => ({ gfm: { _id: 'gfm' } }));
vi.mock('@milkdown/plugin-history',    () => ({ history: { _id: 'history' } }));
vi.mock('@milkdown/plugin-listener',   () => ({ listener: { _id: 'listener' }, listenerCtx: {} }));
vi.mock('@milkdown/plugin-prism',      () => ({ prism: { _id: 'prism' } }));
vi.mock('prismjs/themes/prism-tomorrow.css', () => ({}));
vi.mock('@milkdown/core', () => {
  const useMock = vi.fn().mockImplementation(function (plugin) {
    capturedUsePlugins.push(plugin);
    return this;
  });
  const editorInstance = {
    config: vi.fn().mockReturnThis(),
    use: useMock,
  };
  return {
    defaultValueCtx: {},
    Editor: { make: vi.fn(() => editorInstance) },
    rootCtx: {},
    editorViewOptionsCtx: {},
    editorViewCtx: {},
  };
});
vi.mock('../../utils/wikiLinkPlugin',    () => ({ wikiLinkPlugin: {} }));
vi.mock('../../utils/hashtagPlugin',     () => ({ hashtagPlugin: {} }));
vi.mock('../../utils/livePreviewPlugin', () => ({ livePreviewPlugin: {} }));
vi.mock('../../utils/mathPlugin',        () => ({ mathPlugin: {} }));
vi.mock('../../utils/frontmatter',       () => ({ splitFrontmatter: () => ({ frontmatter: {}, body: '' }) }));
vi.mock('../../utils/markdownCleanup',   () => ({ cleanMilkdownOutput: s => s }));
vi.mock('../../utils/obsidianImagePlugin', () => ({
  obsidianImagePlugin: {},
  obsidianImageNode$: { type: vi.fn() },
  setPendingBlobs: vi.fn(),
  addPendingBlob: vi.fn(),
  isWhitelisted: vi.fn(() => false),
  generateFilename: vi.fn(() => 'test.png'),
  WHITELISTED_EXTS: new Set(['png']),
  WHITELISTED_MIME_TYPES: new Set(['image/png']),
  fileTypeFor: vi.fn(() => 'image'),
  removePendingBlob: vi.fn(),
}));
vi.mock('./EditorErrorBoundary', () => ({ default: ({ children }) => <>{children}</> }));
vi.mock('../organisms/MilkdownEditor.module.css', () => ({ default: {} }));

vi.mock('../../store/useStore', () => {
  const state = {
    currentNotePath: '/vault/Test.md',
    pendingFrontmatter: {},
    pendingRaw: '```python\nprint("Hello")\n```\n',
    isMutable: false,
    editorResetKey: 0,
    updatePending: vi.fn(),
    noteIndex: new Map(),
    openTab: vi.fn(),
    pendingFiles: {},
    addPendingFile: vi.fn(),
    showToast: vi.fn(),
  };
  const useStore = vi.fn((selector) => selector(state));
  useStore.getState = () => state;
  return { default: useStore };
});

describe('MilkdownEditor code block', () => {
  beforeEach(() => {
    capturedUsePlugins = [];
  });

  it('registers the prism plugin in the editor chain', async () => {
    const { default: MilkdownEditor } = await import('./MilkdownEditor');
    render(<MilkdownEditor />);
    const pluginIds = capturedUsePlugins.map(p => p?._id).filter(Boolean);
    expect(pluginIds).toContain('prism');
  });

  it('registers prism after commonmark', async () => {
    const { default: MilkdownEditor } = await import('./MilkdownEditor');
    render(<MilkdownEditor />);
    const ids = capturedUsePlugins.map(p => p?._id).filter(Boolean);
    const cmIdx = ids.indexOf('commonmark');
    const prismIdx = ids.indexOf('prism');
    expect(cmIdx).toBeGreaterThanOrEqual(0);
    expect(prismIdx).toBeGreaterThan(cmIdx);
  });
});

// cleanMilkdownOutput tests live in markdownCleanup.test.js — see that file.
