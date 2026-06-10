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
  ApiError:         class ApiError extends Error { constructor(msg, status) { super(msg); this.status = status; } },
}));

import useStore from './useStore';
import * as api from '../api/notes';

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
