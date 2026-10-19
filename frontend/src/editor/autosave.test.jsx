import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { EditorView } from '@codemirror/view';

vi.mock('../api/notes', () => ({
  fetchNoteContent: vi.fn(), fetchChildren: vi.fn(), fetchNames: vi.fn(), fetchReview: vi.fn(),
  patchNote: vi.fn(), renameNote: vi.fn(), uploadFile: vi.fn(), createNote: vi.fn(),
  ApiError: class ApiError extends Error {},
}));
vi.mock('../utils/media', async (orig) => ({ ...(await orig()), setPendingBlobs: vi.fn() }));

import * as api from '../api/notes';
import useStore from '../store/useStore';
import CmEditor from './CmEditor';

describe('type then switch tab immediately', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.fetchChildren.mockResolvedValue({ parentPath: '/v', filePaths: [], folderPaths: [] });
    api.fetchNames.mockResolvedValue([]);
    api.fetchReview.mockResolvedValue({ notes: [], hasMore: false });
    api.patchNote.mockResolvedValue();
    useStore.setState({ tabs: [], activeTabIndex: -1, settings: { reviewPageSize: 20 }, reviewNotes: [] });
  });

  it('last keystrokes (inside the 120 ms editor debounce) are saved, not dropped', async () => {
    api.fetchNoteContent.mockResolvedValue('hello');
    await useStore.getState().openTab('/v/A.md');
    api.fetchNoteContent.mockResolvedValue('other');
    await useStore.getState().openTab('/v/B.md');
    api.fetchNoteContent.mockResolvedValue('hello');
    await act(async () => { await useStore.getState().switchTab(0); });

    const { container } = render(<CmEditor />);
    const view = EditorView.findFromDOM(container.querySelector('.cm-editor'));
    act(() => { view.dispatch({ changes: { from: 5, insert: ' world' } }); });
    // store has NOT seen the text yet (debounce pending)
    expect(useStore.getState().pendingRaw).toBe('hello');

    await act(async () => { await useStore.getState().switchTab(1); });

    expect(api.patchNote).toHaveBeenCalledTimes(1);
    const [path, hunks] = api.patchNote.mock.calls[0];
    expect(path).toBe('/v/A.md');
    expect(JSON.stringify(hunks)).toContain('world');
    expect(useStore.getState().currentNotePath).toBe('/v/B.md');
  });
});
