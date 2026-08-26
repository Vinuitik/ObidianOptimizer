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

// ── api/tracks mock ────────────────────────────────────────────────────────────

vi.mock('../api/tracks', () => ({
  fetchTracks:        vi.fn(),
  createTrack:        vi.fn(),
  updateTrack:        vi.fn(),
  deleteTrack:        vi.fn(),
  fetchTrackItems:    vi.fn(),
  addTrackItem:       vi.fn(),
  updateTrackItem:    vi.fn(),
  deleteTrackItem:    vi.fn(),
  completeTrackItem:  vi.fn(),
  fetchTrackSchedule: vi.fn(),
  saveTrackSchedule:  vi.fn(),
  fetchTodayPlan:     vi.fn(),
  fetchCapacity:      vi.fn(),
  saveCapacity:       vi.fn(),
  setTrackMode:       vi.fn(),
}));

import useStore from './useStore';
import * as api from '../api/notes';
import * as tracksApi from '../api/tracks';
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
  tracks: [],
  todayItems: [],
  todayMode: 'normal',
  todayOverBudget: false,
  trackItems: {},
  trackSchedules: {},
  trackCapacity: {},
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

describe('logout wipes loaded data', () => {
  beforeEach(() => {
    api.logout.mockResolvedValue();
  });

  it('clears vault tree, note content, tabs, and review data', async () => {
    useStore.setState({
      isAuthenticated: true,
      vaultRoot: '/vault',
      tree: { type: 'folder', children: { a: { type: 'file', fullPath: '/vault/a.md' } }, fullPath: '/vault', loaded: true },
      noteIndex: new Map([['a', '/vault/a.md']]),
      currentNotePath: '/vault/a.md',
      currentNoteRaw: 'secret note content',
      pendingRaw: 'secret edits',
      tabs: [{ path: '/vault/a.md' }],
      activeTabIndex: 0,
      reviewNotes: [{ shortName: 'a', fullPath: '/vault/a.md' }],
    });

    await getState().logout();

    expect(getState().isAuthenticated).toBe(false);
    expect(getState().vaultRoot).toBe('');
    expect(getState().tree.children).toEqual({});
    expect(getState().noteIndex.size).toBe(0);
    expect(getState().currentNotePath).toBeNull();
    expect(getState().currentNoteRaw).toBe('');
    expect(getState().pendingRaw).toBe('');
    expect(getState().tabs).toEqual([]);
    expect(getState().activeTabIndex).toBe(-1);
    expect(getState().reviewNotes).toEqual([]);
  });

  it('revokes pending blob URLs and clears the image plugin map', async () => {
    useStore.setState({
      isAuthenticated: true,
      pendingFiles: { 'img.png': { file: {}, blobURL: 'blob:logout-test' } },
    });

    await getState().logout();

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:logout-test');
    expect(imagePlugin.setPendingBlobs).toHaveBeenCalledWith({});
    expect(getState().pendingFiles).toEqual({});
  });
});

// ── Tracks ────────────────────────────────────────────────────────────────────

describe('tracks: fetchTracks / fetchTodayPlan', () => {
  it('fetchTracks loads the track list', async () => {
    tracksApi.fetchTracks.mockResolvedValue([{ id: 1, title: 'Rust Book' }]);

    await getState().fetchTracks();

    expect(getState().tracks).toEqual([{ id: 1, title: 'Rust Book' }]);
  });

  it('fetchTracks on 401 leaves tracks untouched (no throw)', async () => {
    useStore.setState({ tracks: [{ id: 1, title: 'existing' }] });
    tracksApi.fetchTracks.mockRejectedValue(new api.ApiError('unauthorized', 401));

    await expect(getState().fetchTracks()).resolves.toBeUndefined();
    expect(getState().tracks).toEqual([{ id: 1, title: 'existing' }]);
  });

  it('fetchTodayPlan loads today items, mode, and overBudget', async () => {
    const items = [{ itemId: 10, trackId: 1, trackTitle: 'Rust Book', title: 'Ch1' }];
    tracksApi.fetchTodayPlan.mockResolvedValue({ items, mode: 'lockin', overBudget: false });

    await getState().fetchTodayPlan();

    expect(getState().todayItems).toEqual(items);
    expect(getState().todayMode).toBe('lockin');
    expect(getState().todayOverBudget).toBe(false);
  });

  it('fetchTodayPlan surfaces overBudget for the "you\'re behind" banner', async () => {
    tracksApi.fetchTodayPlan.mockResolvedValue({ items: [], mode: 'normal', overBudget: true });

    await getState().fetchTodayPlan();

    expect(getState().todayOverBudget).toBe(true);
  });

  it('fetchTodayPlan on 401 clears todayItems and resets mode', async () => {
    useStore.setState({ todayItems: [{ itemId: 1 }], todayMode: 'lockin', todayOverBudget: true });
    tracksApi.fetchTodayPlan.mockRejectedValue(new api.ApiError('unauthorized', 401));

    await getState().fetchTodayPlan();

    expect(getState().todayItems).toEqual([]);
    expect(getState().todayMode).toBe('normal');
    expect(getState().todayOverBudget).toBe(false);
  });

  it('setTrackMode persists the mode then re-fetches Today', async () => {
    tracksApi.setTrackMode.mockResolvedValue({ mode: 'lockin' });
    tracksApi.fetchTodayPlan.mockResolvedValue({ items: [], mode: 'lockin', overBudget: false });

    await getState().setTrackMode('lockin');

    expect(tracksApi.setTrackMode).toHaveBeenCalledWith('lockin');
    expect(getState().todayMode).toBe('lockin');
    expect(tracksApi.fetchTodayPlan).toHaveBeenCalled();
  });

  it('fetchTrackCapacity / saveTrackCapacity round-trip the weekday map', async () => {
    tracksApi.fetchCapacity.mockResolvedValue({ 0: 4, 5: 6, 6: 6 });
    await getState().fetchTrackCapacity();
    expect(getState().trackCapacity).toEqual({ 0: 4, 5: 6, 6: 6 });

    tracksApi.saveCapacity.mockResolvedValue({ 0: 4, 2: 9, 5: 6, 6: 6 });
    await getState().saveTrackCapacity({ 2: 9 });
    expect(tracksApi.saveCapacity).toHaveBeenCalledWith({ 2: 9 });
    expect(getState().trackCapacity).toEqual({ 0: 4, 2: 9, 5: 6, 6: 6 });
  });
});

describe('tracks: create / update / delete', () => {
  it('createTrack prepends the new track', async () => {
    useStore.setState({ tracks: [{ id: 1, title: 'Old' }] });
    tracksApi.createTrack.mockResolvedValue({ id: 2, title: 'New Track' });

    await getState().createTrack('New Track', 'book');

    expect(tracksApi.createTrack).toHaveBeenCalledWith('New Track', 'book', {});
    expect(getState().tracks).toEqual([{ id: 2, title: 'New Track' }, { id: 1, title: 'Old' }]);
  });

  it('updateTrack replaces the matching track in place', async () => {
    useStore.setState({ tracks: [{ id: 1, title: 'Old' }, { id: 2, title: 'Other' }] });
    tracksApi.updateTrack.mockResolvedValue({ id: 1, title: 'Renamed' });

    await getState().updateTrack(1, { title: 'Renamed' });

    expect(getState().tracks).toEqual([{ id: 1, title: 'Renamed' }, { id: 2, title: 'Other' }]);
  });

  it('deleteTrack removes the track and its cached items/today entries', async () => {
    useStore.setState({
      tracks: [{ id: 1, title: 'Gone' }, { id: 2, title: 'Stays' }],
      trackItems: { 1: [{ id: 10 }], 2: [{ id: 20 }] },
      todayItems: [{ itemId: 10, trackId: 1 }, { itemId: 20, trackId: 2 }],
    });
    tracksApi.deleteTrack.mockResolvedValue();

    await getState().deleteTrack(1);

    expect(getState().tracks).toEqual([{ id: 2, title: 'Stays' }]);
    expect(getState().trackItems).toEqual({ 2: [{ id: 20 }] });
    expect(getState().todayItems).toEqual([{ itemId: 20, trackId: 2 }]);
  });
});

describe('tracks: items', () => {
  it('addTrackItem appends to the cached list for that track', async () => {
    useStore.setState({ trackItems: { 1: [{ id: 10, title: 'Ch1' }] } });
    tracksApi.addTrackItem.mockResolvedValue({ id: 11, title: 'Ch2' });

    await getState().addTrackItem(1, 'Ch2', null);

    expect(getState().trackItems[1]).toEqual([{ id: 10, title: 'Ch1' }, { id: 11, title: 'Ch2' }]);
  });

  it('updateTrackItem replaces the item and re-sorts by position', async () => {
    useStore.setState({
      trackItems: { 1: [{ id: 10, position: 0 }, { id: 11, position: 1 }, { id: 12, position: 2 }] },
    });
    // Server response reflects the full reorder: 12 moved to the front.
    tracksApi.updateTrackItem.mockResolvedValue({ id: 12, position: 0 });

    await getState().updateTrackItem(1, 12, { position: 0 });

    // Local reorder: 12 removed, reinserted at index 0, everyone renumbered.
    expect(getState().trackItems[1].map(i => i.id)).toEqual([12, 10, 11]);
    expect(getState().trackItems[1].map(i => i.position)).toEqual([0, 1, 2]);
  });

  it('deleteTrackItem removes it from the cached list', async () => {
    useStore.setState({ trackItems: { 1: [{ id: 10 }, { id: 11 }] } });
    tracksApi.deleteTrackItem.mockResolvedValue();

    await getState().deleteTrackItem(1, 10);

    expect(getState().trackItems[1]).toEqual([{ id: 11 }]);
  });

  it('completeTrackItem removes it from todayItems and updates trackItems caches', async () => {
    useStore.setState({
      todayItems: [{ itemId: 10, trackId: 1 }, { itemId: 20, trackId: 2 }],
      trackItems: { 1: [{ id: 10, status: 'pending' }], 2: [{ id: 20, status: 'pending' }] },
    });
    tracksApi.completeTrackItem.mockResolvedValue({ id: 10, status: 'done' });

    await getState().completeTrackItem(10, false);

    expect(tracksApi.completeTrackItem).toHaveBeenCalledWith(10, false);
    expect(getState().todayItems).toEqual([{ itemId: 20, trackId: 2 }]);
    expect(getState().trackItems['1']).toEqual([{ id: 10, status: 'done' }]);
    expect(getState().trackItems['2']).toEqual([{ id: 20, status: 'pending' }]);
  });
});

describe('tracks: schedule', () => {
  it('fetchTrackSchedule caches by trackId', async () => {
    tracksApi.fetchTrackSchedule.mockResolvedValue({ 0: 2 });

    await getState().fetchTrackSchedule(1);

    expect(getState().trackSchedules[1]).toEqual({ 0: 2 });
  });

  it('updateSchedule saves and re-caches the returned schedule', async () => {
    tracksApi.saveTrackSchedule.mockResolvedValue({ 0: 3, 2: 1 });

    await getState().updateSchedule(1, { 0: 3, 2: 1 });

    expect(tracksApi.saveTrackSchedule).toHaveBeenCalledWith(1, { 0: 3, 2: 1 });
    expect(getState().trackSchedules[1]).toEqual({ 0: 3, 2: 1 });
  });
});

describe('tracks: wiped on logout', () => {
  it('clears tracks, todayItems, trackItems, trackSchedules', async () => {
    api.logout.mockResolvedValue();
    useStore.setState({
      isAuthenticated: true,
      tracks: [{ id: 1 }],
      todayItems: [{ itemId: 1 }],
      trackItems: { 1: [{ id: 10 }] },
      trackSchedules: { 1: { 0: 2 } },
    });

    await getState().logout();

    expect(getState().tracks).toEqual([]);
    expect(getState().todayItems).toEqual([]);
    expect(getState().trackItems).toEqual({});
    expect(getState().trackSchedules).toEqual({});
  });
});
