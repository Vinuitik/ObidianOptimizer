import { describe, it, expect, vi, beforeEach } from 'vitest';

// ── API mock ─────────────────────────────────────────────────────────────────

vi.mock('../api/notes', () => ({
  fetchNoteContent: vi.fn(),
  fetchChildren:    vi.fn(),
  fetchNames:       vi.fn(),
  fetchReview:      vi.fn(),
  checkAuth:        vi.fn(),
  login:            vi.fn(),
  logout:           vi.fn(),
  createNote:       vi.fn(),
  patchNote:        vi.fn(),
  renameNote:       vi.fn(),
  deleteNote:       vi.fn(),
  createFolder:     vi.fn(),
  moveNote:         vi.fn(),
  fetchSettings:    vi.fn(),
  saveSettings:     vi.fn(),
  uploadFile:       vi.fn(),
  ApiError:         class ApiError extends Error { constructor(msg, status) { super(msg); this.status = status; } },
}));

// ── obsidianImagePlugin mock ──────────────────────────────────────────────────

vi.mock('../utils/obsidianImagePlugin', () => ({
  setPendingBlobs: vi.fn(),
}));

import useStore from './useStore';
import * as api from '../api/notes';
import * as imagePlugin from '../utils/obsidianImagePlugin';

// Global browser API stubs
global.URL.revokeObjectURL = vi.fn();
global.URL.createObjectURL = vi.fn(() => 'blob:mock');

// ── Helpers ───────────────────────────────────────────────────────────────────

const INITIAL = {
  tabs: [],
  activeTabIndex: -1,
  currentNoteRaw: '',
  currentNotePath: null,
  pendingRaw: '',
  pendingFrontmatter: '',
  pendingTitle: '',
  isMutable: false,
  editorResetKey: 0,
  centerMode: 'view',
  noteIndex: new Map(),
  pendingFiles: {},
  toast: null,
};

function resetStore() {
  // Merge (no replace flag) so action functions defined in create() are preserved
  useStore.setState(INITIAL);
}

function getState() {
  return useStore.getState();
}

// ── Tests ─────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks();
  // Default resolutions so side-effect calls inside actions don't throw
  api.fetchReview.mockResolvedValue({ notes: [], hasMore: false });
  api.fetchNames.mockResolvedValue([]);
  api.fetchChildren.mockResolvedValue({ parentPath: '', filePaths: [], folderPaths: [] });
  resetStore();
});

// ── openTab ───────────────────────────────────────────────────────────────────

describe('openTab', () => {
  it('adds a new tab and fetches content', async () => {
    api.fetchNoteContent.mockResolvedValue('---\nsr-due: 2025-01-01\n---\n\nBody.');
    await getState().openTab('/vault/Note.md');
    const { tabs, activeTabIndex } = getState();
    expect(tabs).toHaveLength(1);
    expect(tabs[0].path).toBe('/vault/Note.md');
    expect(activeTabIndex).toBe(0);
    expect(api.fetchNoteContent).toHaveBeenCalledWith('/vault/Note.md');
  });

  it('does not add a duplicate tab if path already open', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/A.md');
    await getState().openTab('/vault/A.md');
    expect(getState().tabs).toHaveLength(1);
    expect(api.fetchNoteContent).toHaveBeenCalledTimes(1);
  });

  it('switches to existing tab without fetching again', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    // Open a second tab so activeTabIndex becomes 1
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    expect(getState().activeTabIndex).toBe(1);
    // Now open A again — should switch to index 0, not add a third tab
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    expect(getState().tabs).toHaveLength(2);
    expect(getState().activeTabIndex).toBe(0);
  });

  it('sets isMutable false on new tab', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    expect(getState().isMutable).toBe(false);
  });
});

// ── _snapshotTab ──────────────────────────────────────────────────────────────

describe('_snapshotTab', () => {
  it('stores hunks in the active tab entry', async () => {
    api.fetchNoteContent.mockResolvedValue('line1\nline2');
    await getState().openTab('/vault/Note.md');
    // Simulate an edit that differs from disk
    useStore.setState({ pendingRaw: 'line1\nline2\nline3' });
    getState()._snapshotTab();
    const { tabs } = getState();
    expect(tabs[0].hunks.length).toBeGreaterThan(0);
  });

  it('stores empty hunks when no edits', async () => {
    api.fetchNoteContent.mockResolvedValue('line1\nline2');
    await getState().openTab('/vault/Note.md');
    getState()._snapshotTab();
    expect(getState().tabs[0].hunks).toHaveLength(0);
  });

  it('is a no-op when there is no active tab', () => {
    expect(() => getState()._snapshotTab()).not.toThrow();
  });
});

// ── switchTab ─────────────────────────────────────────────────────────────────

describe('switchTab', () => {
  it('re-fetches disk content on switch', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    // Now switch back to tab 0
    api.fetchNoteContent.mockResolvedValue('# A updated');
    await getState().switchTab(0);
    expect(api.fetchNoteContent).toHaveBeenLastCalledWith('/vault/A.md');
    expect(getState().activeTabIndex).toBe(0);
  });

  it('restores pending edits via applyHunks when tab has stored hunks', async () => {
    api.fetchNoteContent.mockResolvedValue('line1\nline2');
    await getState().openTab('/vault/A.md');
    // Edit tab 0 then open tab 1 — this snapshots tab 0's edits
    useStore.setState({ pendingRaw: 'line1\nline2\nADDED' });
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    // Switch back — should re-apply the stored hunk
    api.fetchNoteContent.mockResolvedValue('line1\nline2');
    await getState().switchTab(0);
    expect(getState().pendingRaw).toBe('line1\nline2\nADDED');
  });
});

// ── closeTab ──────────────────────────────────────────────────────────────────

describe('closeTab', () => {
  it('clears editor when last tab is closed', async () => {
    api.fetchNoteContent.mockResolvedValue('# Only');
    await getState().openTab('/vault/Only.md');
    await getState().closeTab(0);
    const { tabs, activeTabIndex, currentNotePath } = getState();
    expect(tabs).toHaveLength(0);
    expect(activeTabIndex).toBe(-1);
    expect(currentNotePath).toBeNull();
  });

  it('activates nearest remaining tab when active tab closed', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    // Active is index 1; close it → should switch to index 0
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().closeTab(1);
    expect(getState().tabs).toHaveLength(1);
    expect(getState().tabs[0].path).toBe('/vault/A.md');
  });

  it('adjusts activeTabIndex when an earlier tab is closed', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    // Active is index 1; close index 0 (inactive) → activeTabIndex should become 0
    await getState().closeTab(0);
    expect(getState().activeTabIndex).toBe(0);
    expect(getState().tabs[0].path).toBe('/vault/B.md');
  });

  it('does not adjust activeTabIndex when a later tab is closed', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    // Switch back to tab 0 so it's active
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().switchTab(0);
    // Close tab 1 (inactive, later) → activeTabIndex stays 0
    await getState().closeTab(1);
    expect(getState().activeTabIndex).toBe(0);
  });

  // Regression: closing an active NON-LAST tab used to leave activeTabIndex
  // equal to the new index, so switchTab early-returned and the editor kept
  // showing the closed note's content.
  it('loads the next tab content when closing an active middle tab', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    api.fetchNoteContent.mockResolvedValue('# C');
    await getState().openTab('/vault/C.md');
    // Make A (index 0) active, then close it
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().switchTab(0);
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().closeTab(0);

    expect(getState().tabs.map(t => t.path)).toEqual(['/vault/B.md', '/vault/C.md']);
    expect(getState().activeTabIndex).toBe(0);
    expect(getState().currentNotePath).toBe('/vault/B.md');
    expect(getState().currentNoteRaw).toBe('# B');
  });

  it('does not snapshot the closed tab state onto the tab that takes its slot', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    // Make A active and give it unsaved edits
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().switchTab(0);
    useStore.setState({ pendingRaw: '# A\nedited but discarded', isMutable: true });
    // Close A — its dirty state must NOT leak into B (which moves into slot 0)
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().closeTab(0);

    expect(getState().tabs[0].path).toBe('/vault/B.md');
    expect(getState().tabs[0].hunks).toHaveLength(0);
    expect(getState().tabs[0].isMutable).toBe(false);
    expect(getState().pendingRaw).toBe('# B');
  });
});

// ── cancelEdit ────────────────────────────────────────────────────────────────

describe('cancelEdit', () => {
  it('resets pendingRaw to currentNoteRaw', async () => {
    api.fetchNoteContent.mockResolvedValue('original content');
    await getState().openTab('/vault/Note.md');
    useStore.setState({ pendingRaw: 'edited content', isMutable: true });
    getState().cancelEdit();
    expect(getState().pendingRaw).toBe('original content');
  });

  it('sets isMutable to false', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    useStore.setState({ isMutable: true });
    getState().cancelEdit();
    expect(getState().isMutable).toBe(false);
  });

  it('increments editorResetKey', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    const before = getState().editorResetKey;
    getState().cancelEdit();
    expect(getState().editorResetKey).toBe(before + 1);
  });

  it('clears hunks on the active tab', async () => {
    api.fetchNoteContent.mockResolvedValue('line1\nline2');
    await getState().openTab('/vault/Note.md');
    useStore.setState({ pendingRaw: 'line1\nline2\nextra' });
    getState()._snapshotTab();
    expect(getState().tabs[0].hunks.length).toBeGreaterThan(0);
    getState().cancelEdit();
    expect(getState().tabs[0].hunks).toHaveLength(0);
  });
});

// ── syncNote ──────────────────────────────────────────────────────────────────

describe('syncNote', () => {
  it('does not call patchNote when content is unchanged', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note\n\nSame content');
    await getState().openTab('/vault/Note.md');
    // pendingRaw already equals currentNoteRaw
    await getState().syncNote();
    expect(api.patchNote).not.toHaveBeenCalled();
  });

  it('calls patchNote when content has changed', async () => {
    api.fetchNoteContent.mockResolvedValue('line1\nline2');
    api.fetchChildren.mockResolvedValue({ parentPath: '/vault', folderPaths: [], filePaths: [] });
    api.fetchNames.mockResolvedValue([]);
    api.fetchReview.mockResolvedValue({ notes: [], hasMore: false });
    api.patchNote.mockResolvedValue();
    await getState().openTab('/vault/Note.md');
    useStore.setState({ pendingRaw: 'line1\nline2\nline3', settings: { reviewPageSize: 20 } });
    await getState().syncNote();
    expect(api.patchNote).toHaveBeenCalledWith('/vault/Note.md', expect.any(Array));
  });

  it('calls renameNote when title changed', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    api.fetchChildren.mockResolvedValue({ parentPath: '/vault', folderPaths: [], filePaths: [] });
    api.fetchNames.mockResolvedValue([]);
    api.fetchReview.mockResolvedValue({ notes: [], hasMore: false });
    api.renameNote.mockResolvedValue({ path: '/vault/NewName.md' });
    api.patchNote.mockResolvedValue();
    await getState().openTab('/vault/Note.md');
    useStore.setState({ pendingTitle: 'NewName', settings: { reviewPageSize: 20 } });
    await getState().syncNote();
    expect(api.renameNote).toHaveBeenCalledWith('/vault/Note.md', 'NewName');
  });

  it('sets isMutable to false after save', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    api.fetchChildren.mockResolvedValue({ parentPath: '/vault', folderPaths: [], filePaths: [] });
    api.fetchNames.mockResolvedValue([]);
    api.fetchReview.mockResolvedValue({ notes: [], hasMore: false });
    await getState().openTab('/vault/Note.md');
    useStore.setState({ isMutable: true, settings: { reviewPageSize: 20 } });
    await getState().syncNote();
    expect(getState().isMutable).toBe(false);
  });
});

// ── addPendingFile ────────────────────────────────────────────────────────────

describe('addPendingFile', () => {
  it('adds entry to pendingFiles', () => {
    const file = { name: 'photo.png' };
    getState().addPendingFile('photo-abc.png', file, 'blob:url-1');
    expect(getState().pendingFiles['photo-abc.png']).toEqual({ file, blobURL: 'blob:url-1' });
  });

  it('accumulates multiple files', () => {
    getState().addPendingFile('a.png', {}, 'blob:a');
    getState().addPendingFile('b.mp4', {}, 'blob:b');
    expect(Object.keys(getState().pendingFiles)).toHaveLength(2);
  });
});

// ── _snapshotTab saves pendingFiles ───────────────────────────────────────────

describe('_snapshotTab saves pendingFiles', () => {
  it('snapshots current pendingFiles into the active tab', async () => {
    api.fetchNoteContent.mockResolvedValue('line1');
    await getState().openTab('/vault/A.md');
    useStore.setState({ pendingFiles: { 'img-abc.png': { file: {}, blobURL: 'blob:x' } } });
    getState()._snapshotTab();
    expect(getState().tabs[0].pendingFiles).toEqual({ 'img-abc.png': { file: {}, blobURL: 'blob:x' } });
  });
});

// ── switchTab restores pendingFiles ───────────────────────────────────────────

describe('switchTab restores pendingFiles', () => {
  it('restores pendingFiles from tab snapshot and calls setPendingBlobs', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    const files = { 'img-abc.png': { file: {}, blobURL: 'blob:x' } };
    useStore.setState({ pendingFiles: files });

    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md'); // snapshots A's pendingFiles into tab 0

    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().switchTab(0); // should restore A's pendingFiles

    expect(getState().pendingFiles).toEqual(files);
    expect(imagePlugin.setPendingBlobs).toHaveBeenCalledWith(files);
  });

  it('restores empty pendingFiles for a tab with no pasted files', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    api.fetchNoteContent.mockResolvedValue('# B');
    await getState().openTab('/vault/B.md');
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().switchTab(0);
    expect(getState().pendingFiles).toEqual({});
  });
});

// ── closeTab revokes blob URLs ────────────────────────────────────────────────

describe('closeTab revokes blob URLs', () => {
  it('calls revokeObjectURL for each pending file when closing a tab', async () => {
    api.fetchNoteContent.mockResolvedValue('# A');
    await getState().openTab('/vault/A.md');
    // Manually inject pendingFiles into the tab entry
    const updated = [...getState().tabs];
    updated[0] = { ...updated[0], pendingFiles: { 'img.png': { file: {}, blobURL: 'blob:revoke-me' } } };
    useStore.setState({ tabs: updated });

    await getState().closeTab(0);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:revoke-me');
  });

  it('clears pendingFiles from store when last tab is closed', async () => {
    api.fetchNoteContent.mockResolvedValue('# Only');
    await getState().openTab('/vault/Only.md');
    useStore.setState({ pendingFiles: { 'x.png': { file: {}, blobURL: 'blob:x' } } });
    await getState().closeTab(0);
    expect(getState().pendingFiles).toEqual({});
  });
});

// ── cancelEdit revokes blob URLs ──────────────────────────────────────────────

describe('cancelEdit revokes blob URLs', () => {
  it('calls revokeObjectURL for each pending file', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    useStore.setState({
      pendingFiles: {
        'a.png': { file: {}, blobURL: 'blob:a' },
        'b.mp4': { file: {}, blobURL: 'blob:b' },
      },
    });
    getState().cancelEdit();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:a');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:b');
  });

  it('clears pendingFiles after cancel', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    useStore.setState({ pendingFiles: { 'x.png': { file: {}, blobURL: 'blob:x' } } });
    getState().cancelEdit();
    expect(getState().pendingFiles).toEqual({});
  });
});

// ── syncNote uploads pending files ────────────────────────────────────────────

describe('syncNote with pendingFiles', () => {
  beforeEach(() => {
    api.fetchChildren.mockResolvedValue({ parentPath: '/vault', folderPaths: [], filePaths: [] });
    api.fetchNames.mockResolvedValue([]);
    api.fetchReview.mockResolvedValue({ notes: [], hasMore: false });
    api.patchNote.mockResolvedValue();
    api.uploadFile.mockResolvedValue({ filename: 'img-abc.png', url: '/api/images/img-abc.png' });
  });

  it('calls uploadFile for each pending file before patching', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    const file = { name: 'img.png' };
    useStore.setState({
      pendingFiles: { 'img-abc.png': { file, blobURL: 'blob:img' } },
      settings: { reviewPageSize: 20 },
    });
    await getState().syncNote();
    expect(api.uploadFile).toHaveBeenCalledWith(file, 'img-abc.png');
  });

  it('clears pendingFiles after successful upload', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    useStore.setState({
      pendingFiles: { 'img-abc.png': { file: {}, blobURL: 'blob:img' } },
      settings: { reviewPageSize: 20 },
    });
    await getState().syncNote();
    expect(getState().pendingFiles).toEqual({});
  });

  it('calls revokeObjectURL after upload', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    useStore.setState({
      pendingFiles: { 'img-abc.png': { file: {}, blobURL: 'blob:img-upload' } },
      settings: { reviewPageSize: 20 },
    });
    await getState().syncNote();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:img-upload');
  });

  it('does not call uploadFile when there are no pending files', async () => {
    api.fetchNoteContent.mockResolvedValue('# Note');
    await getState().openTab('/vault/Note.md');
    useStore.setState({ settings: { reviewPageSize: 20 } });
    await getState().syncNote();
    expect(api.uploadFile).not.toHaveBeenCalled();
  });
});
