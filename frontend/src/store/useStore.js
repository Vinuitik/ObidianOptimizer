import { create } from 'zustand';
import {
  ApiError,
  fetchNames, fetchChildren,
  checkAuth, login as apiLogin, logout as apiLogout,
  createNote as apiCreate, patchNote as apiPatch,
  renameNote as apiRename, deleteNote as apiDelete,
  createFolder as apiCreateFolder, deleteFolder as apiDeleteFolder,
  moveNote as apiMoveNote,
  fetchSettings as apiFetchSettings, saveSettings as apiSaveSettings,
  uploadFile as apiUploadFile,
} from '../api/notes';
import {
  fetchTracks as apiFetchTracks, createTrack as apiCreateTrack, updateTrack as apiUpdateTrack,
  deleteTrack as apiDeleteTrack, fetchTrackItems as apiFetchTrackItems,
  addTrackItem as apiAddTrackItem, updateTrackItem as apiUpdateTrackItem,
  deleteTrackItem as apiDeleteTrackItem, completeTrackItem as apiCompleteTrackItem,
  fetchTrackSchedule as apiFetchTrackSchedule, saveTrackSchedule as apiSaveTrackSchedule,
  fetchTodayPlan as apiFetchTodayPlan,
  fetchCapacity as apiFetchCapacity, saveCapacity as apiSaveCapacity,
  setTrackMode as apiSetTrackMode,
  fetchTrackProgress as apiFetchTrackProgress,
} from '../api/tracks';
// Offline-aware drop-ins: identical to the api/notes versions when online (they
// just delegate), but fall back to the downloaded IndexedDB subset when offline.
// This is what makes review work with no network — see pwa/offlineApi.js.
import {
  fetchReviewOffline as fetchReview,
  fetchNoteContentOffline as fetchNoteContent,
  isDriveMode,
} from '../pwa/offlineApi';
import { allocateTracks } from '../pwa/reviewPlan';
import { getMeta } from '../pwa/db';
import { setPendingBlobs } from '../utils/obsidianImagePlugin';
import { computeHunks, applyHunks } from '../utils/diff';
import { splitFrontmatter, joinFrontmatter } from '../utils/frontmatter';

// ── Review session (localStorage) ────────────────────────────────────────────

const REVIEW_KEY = 'obsOpt_reviewSession';

// "Signed in on this device" — persisted so a COLD boot (esp. offline: server unreachable,
// `/me` can't be checked) starts authenticated instead of walling the downloaded set behind a
// login it can't complete. The session cookie itself already persists in the browser; this is
// just the app-side flag that used to reset to false on every launch. Cleared on a real 401.
const AUTH_KEY = 'obsOpt_authOk';
const persistedAuth = () => { try { return localStorage.getItem(AUTH_KEY) === '1'; } catch { return false; } };
const setPersistedAuth = (ok) => { try { localStorage.setItem(AUTH_KEY, ok ? '1' : '0'); } catch {} };

function getReviewSession() {
  const today = new Date().toISOString().slice(0, 10);
  try {
    const stored = JSON.parse(localStorage.getItem(REVIEW_KEY) || 'null');
    if (stored?.date === today) {
      return { offset: stored.offset ?? 0, flashcardsDone: stored.flashcardsDone ?? 0 };
    }
  } catch {}
  // New calendar day → reset offset AND the flashcard-done counter (fresh daily budget).
  localStorage.setItem(REVIEW_KEY, JSON.stringify({ date: today, offset: 0, flashcardsDone: 0 }));
  return { offset: 0, flashcardsDone: 0 };
}

function saveReviewSession(patch) {
  const today = new Date().toISOString().slice(0, 10);
  const cur = getReviewSession();
  localStorage.setItem(REVIEW_KEY, JSON.stringify({ date: today, ...cur, ...patch }));
}

function saveReviewOffset(offset) {
  saveReviewSession({ offset });
}

// One more flashcard test finished today → shrink today's remaining flashcard budget
// so a mid-day reload won't re-offer flashcard slots past maxDailyFlashcards.
function bumpFlashcardsDone() {
  const { flashcardsDone } = getReviewSession();
  saveReviewSession({ flashcardsDone: flashcardsDone + 1 });
}

// ── Tree helpers ──────────────────────────────────────────────────────────────

function getParentPath(fullPath) {
  const sep = fullPath.includes('\\') ? '\\' : '/';
  return fullPath.split(/[/\\]/).slice(0, -1).join(sep);
}

function mergeChildren(tree, folderPath, newChildren) {
  if (tree.fullPath === folderPath) {
    return { ...tree, children: newChildren, loaded: true };
  }
  const updated = {};
  for (const [name, node] of Object.entries(tree.children)) {
    updated[name] = node.type === 'folder'
      ? mergeChildren(node, folderPath, newChildren)
      : node;
  }
  return { ...tree, children: updated };
}

function childrenFromResponse({ folderPaths, filePaths }) {
  const children = {};
  for (const fp of folderPaths) {
    const name = fp.split(/[/\\]/).pop();
    children[name] = { type: 'folder', children: {}, fullPath: fp, loaded: false };
  }
  for (const fp of filePaths) {
    const name = fp.split(/[/\\]/).pop();
    children[name] = { type: 'file', fullPath: fp };
  }
  return children;
}

function indexEntries(filePaths) {
  return filePaths.map(fp => [
    fp.split(/[/\\]/).pop().replace(/\.md$/i, '').toLowerCase(),
    fp,
  ]);
}

// ── Review list builder ───────────────────────────────────────────────────────

// Input: allocated notes [{ path, hasCards, track }]. Produces the review list items
// the UI renders, keeping track/hasCards and de-duplicating display names.
function buildReviewList(notes) {
  const used = {};
  const seen = new Set();
  return notes.map(({ path: fullPath, track, hasCards }) => {
    let base   = fullPath.split(/[/\\]/).pop().replace(/\.md$/, '');
    let unique = base;
    let count  = used[base] || 0;
    while (seen.has(unique)) { count += 1; unique = `${base} (${count})`; }
    used[base] = count;
    seen.add(unique);
    return { shortName: unique, fullPath, track, hasCards };
  });
}

function noteBasename(fullPath) {
  return fullPath?.split(/[/\\]/).pop().replace(/\.md$/, '') ?? '';
}

// ── Store ─────────────────────────────────────────────────────────────────────

// Everything loaded from the vault/backend — created fresh at startup and
// wiped on logout so no note content survives signing out.
const initialDataState = () => ({
  // Vault tree (lazily populated)
  tree: { type: 'folder', children: {}, fullPath: '', loaded: false },
  vaultRoot: '',
  noteIndex: new Map(),

  // Current note — source of truth (what's on disk)
  currentNoteRaw: '',
  currentNotePath: null,

  // Pending (working copy while editing)
  pendingRaw: '',
  pendingFrontmatter: '',
  pendingTitle: '',

  // Whether the editor is in editable mode
  isMutable: false,

  // Center panel mode: 'view' | 'new'
  // 'edit' is now conveyed by isMutable; centerMode only distinguishes new-note form
  centerMode: 'view',
  newNoteFolder: null,
  newNoteName: '',

  // Review
  reviewNotes: [],
  reviewOffset: 0,
  reviewHasMore: false,

  // Learning Tracks (see tracks/FLOWS.md) — trackItems keyed by trackId, populated
  // lazily as "Manage tracks" opens each track's detail.
  tracks: [],
  todayItems: [],
  todayMode: 'normal',
  todayOverBudget: false,
  trackItems: {},
  trackSchedules: {},
  trackCapacity: {}, // weekday(0=Mon..6=Sun) -> items/day ceiling (Phase 1c)
  trackProgress: [], // Phase 1d — [{ id, title, type, itemsDone, itemsTotal, deadline, onTrack }]

  // Settings (loaded from backend on startup)
  settings: { vaultPath: '', resourcePath: '', reviewPageSize: 20, startupSyncMode: 'blocking', flashcardsEnabled: true, tracksEnabled: true, maxDailyReviews: 50, maxDailyFlashcards: 20 },

  // Toast notification: null | { message: string }
  toast: null,

  // Files pasted but not yet uploaded: { [filename]: { file: File, blobURL: string } }
  pendingFiles: {},

  // Tabs — each entry: { path, pendingTitle, isMutable, hunks, pendingFiles }
  // hunks stores the diff from currentNoteRaw → pendingRaw for inactive tabs.
  // pendingFiles stores pasted-but-unsaved files for inactive tabs.
  tabs: [],
  activeTabIndex: -1,
});

const useStore = create((set, get) => ({
  ...initialDataState(),

  // Incremented on cancel to force Milkdown remount with clean content
  editorResetKey: 0,

  // Auth — seed from the persisted flag so an offline cold boot isn't walled (checkAuth
  // corrects it to false only on a real 401 from a reachable server).
  isAuthenticated: persistedAuth(),
  showLogin: false,

  // Panel collapse — start closed on phones, where the panels render as
  // overlay drawers (SplitLayout.module.css) and would cover the editor
  leftCollapsed: typeof window?.matchMedia === 'function' && window.matchMedia('(max-width: 768px)').matches,
  rightCollapsed: typeof window?.matchMedia === 'function' && window.matchMedia('(max-width: 768px)').matches,


  // ── Toast ─────────────────────────────────────────────────────────────────

  showToast: (message) => {
    set({ toast: { message } });
    setTimeout(() => set(s => s.toast?.message === message ? { toast: null } : {}), 4000);
  },

  clearToast: () => set({ toast: null }),

  // ── Pending file uploads ──────────────────────────────────────────────────

  addPendingFile: (filename, file, blobURL) => {
    set(s => ({ pendingFiles: { ...s.pendingFiles, [filename]: { file, blobURL } } }));
  },

  // ── Tree: initial root load ───────────────────────────────────────────────

  fetchRootChildren: async () => {
    try {
      const data = await fetchChildren(null);
      const children = childrenFromResponse(data);
      const tree = { type: 'folder', children, fullPath: data.parentPath, loaded: true };
      const noteIndex = new Map(indexEntries(data.filePaths));
      set({ tree, vaultRoot: data.parentPath, noteIndex });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        set({ tree: { type: 'folder', children: {}, fullPath: '', loaded: false }, vaultRoot: '', noteIndex: new Map() });
      } else throw e;
    }
  },

  // ── Tree: on-demand folder expand ────────────────────────────────────────

  fetchChildrenOf: async (folderPath) => {
    try {
      const data = await fetchChildren(folderPath);
      const children = childrenFromResponse(data);
      set(s => ({
        tree: mergeChildren(s.tree, folderPath, children),
        noteIndex: new Map([...s.noteIndex, ...indexEntries(data.filePaths)]),
      }));
    } catch (e) {
      console.error('Failed to load folder contents:', e);
    }
  },

  // ── Full noteIndex rebuild ────────────────────────────────────────────────

  fetchNoteNames: async () => {
    try {
      const paths = await fetchNames();
      const noteIndex = new Map(
        paths.map(p => [p.split(/[/\\]/).pop().replace(/\.md$/i, '').toLowerCase(), p])
      );
      set({ noteIndex });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        set({ noteIndex: new Map() });
      }
    }
  },

  // ── Review ────────────────────────────────────────────────────────────────

  initReviewSession: async () => {
    const { offset } = getReviewSession();
    await get().fetchReviewNotes(offset);
  },

  fetchReviewNotes: async (offset = 0) => {
    let { maxDailyReviews, maxDailyFlashcards, flashcardsEnabled } = get().settings;
    // Offline phone (Drive mode): /settings is unreachable and the store may still hold
    // initial defaults, so the caps the Drive bundle carried (cached in IDB) win — this
    // is what keeps the offline hybrid split identical to the desktop.
    if (isDriveMode()) {
      try {
        const caps = await getMeta('reviewCaps');
        if (caps) {
          maxDailyReviews    = caps.maxDailyReviews    ?? maxDailyReviews;
          maxDailyFlashcards = caps.maxDailyFlashcards ?? maxDailyFlashcards;
          flashcardsEnabled  = caps.flashcardsEnabled  ?? flashcardsEnabled;
        }
      } catch { /* fall back to store/defaults */ }
    }
    const totalMax = maxDailyReviews ?? 50;         // total-per-day ceiling = fetch size
    try {
      const { notes, hasMore } = await fetchReview(offset, totalMax);
      // Split into flashcard vs read tracks under the caps. flashcardsDone shrinks
      // the flashcard budget so the day never exceeds maxDailyFlashcards.
      const { flashcardsDone } = getReviewSession();
      // Global flashcards-off = 0 flashcard budget → the whole day is read-and-self-grade.
      const flashcardMax = flashcardsEnabled === false ? 0 : (maxDailyFlashcards ?? 20);
      const allocated = allocateTracks(notes, {
        flashcardMax,
        totalMax,
        flashcardsDoneToday: flashcardsDone,
      });
      set({ reviewNotes: buildReviewList(allocated), reviewOffset: offset, reviewHasMore: hasMore });
      saveReviewOffset(offset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        set({ reviewNotes: [], reviewHasMore: false });
      } else throw e;
    }
  },

  loadMoreReview: async () => {
    const { reviewOffset, settings } = get();
    await get().fetchReviewNotes(reviewOffset + (settings.maxDailyReviews ?? 50));
  },

  dismissFromReview: (fullPath) => {
    set(s => ({ reviewNotes: s.reviewNotes.filter(n => n.fullPath !== fullPath) }));
  },

  // A flashcard test finished → count it against today's flashcard budget so a reload
  // won't re-offer flashcard slots past the cap. (Read/self-grade reviews don't count.)
  recordFlashcardDone: () => { bumpFlashcardsDone(); },

  // ── Tracks ────────────────────────────────────────────────────────────────

  fetchTracks: async () => {
    try {
      const tracks = await apiFetchTracks();
      set({ tracks });
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) throw e;
    }
  },

  fetchTodayPlan: async () => {
    try {
      const plan = await apiFetchTodayPlan();
      set({ todayItems: plan.items, todayMode: plan.mode, todayOverBudget: plan.overBudget });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        set({ todayItems: [], todayMode: 'normal', todayOverBudget: false });
      } else throw e;
    }
  },

  setTrackMode: async (mode) => {
    await apiSetTrackMode(mode);
    set({ todayMode: mode });
    await get().fetchTodayPlan(); // re-derive the list for the new mode
  },

  fetchTrackCapacity: async () => {
    try {
      const trackCapacity = await apiFetchCapacity();
      set({ trackCapacity });
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) throw e;
    }
  },

  saveTrackCapacity: async (weekdayCapacities) => {
    const trackCapacity = await apiSaveCapacity(weekdayCapacities);
    set({ trackCapacity });
    return trackCapacity;
  },

  fetchTrackProgress: async () => {
    try {
      const trackProgress = await apiFetchTrackProgress();
      set({ trackProgress });
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) throw e;
    }
  },

  createTrack: async (title, type) => {
    const track = await apiCreateTrack(title, type);
    set(s => ({ tracks: [track, ...s.tracks] }));
    return track;
  },

  updateTrack: async (id, patch) => {
    const updated = await apiUpdateTrack(id, patch);
    set(s => ({ tracks: s.tracks.map(t => t.id === id ? updated : t) }));
    return updated;
  },

  deleteTrack: async (id) => {
    await apiDeleteTrack(id);
    set(s => ({
      tracks: s.tracks.filter(t => t.id !== id),
      trackItems: Object.fromEntries(Object.entries(s.trackItems).filter(([k]) => Number(k) !== id)),
      todayItems: s.todayItems.filter(i => i.trackId !== id),
    }));
  },

  fetchTrackItems: async (trackId) => {
    const items = await apiFetchTrackItems(trackId);
    set(s => ({ trackItems: { ...s.trackItems, [trackId]: items } }));
    return items;
  },

  addTrackItem: async (trackId, title, notePath) => {
    const item = await apiAddTrackItem(trackId, title, notePath);
    set(s => ({ trackItems: { ...s.trackItems, [trackId]: [...(s.trackItems[trackId] ?? []), item] } }));
    return item;
  },

  updateTrackItem: async (trackId, itemId, patch) => {
    const updated = await apiUpdateTrackItem(itemId, patch);
    set(s => {
      const current = s.trackItems[trackId] ?? [];
      let next;
      if (patch.position != null) {
        // A reorder: sibling positions in the cache go stale the moment this item moves
        // (the server renumbers everyone, we only get this item's row back), so sorting
        // by position would misplace ties. Simulate the same remove+reinsert+renumber the
        // backend does instead of trusting stale sibling data.
        const withoutMoved = current.filter(i => i.id !== itemId);
        const insertAt = Math.max(0, Math.min(patch.position, withoutMoved.length));
        withoutMoved.splice(insertAt, 0, updated);
        next = withoutMoved.map((item, idx) => ({ ...item, position: idx }));
      } else {
        next = current.map(i => i.id === itemId ? updated : i);
      }
      return { trackItems: { ...s.trackItems, [trackId]: next } };
    });
    return updated;
  },

  deleteTrackItem: async (trackId, itemId) => {
    await apiDeleteTrackItem(itemId);
    set(s => ({
      trackItems: { ...s.trackItems, [trackId]: (s.trackItems[trackId] ?? []).filter(i => i.id !== itemId) },
    }));
  },

  // Optimistically removes the item from Today (mirrors dismissFromReview) — the item
  // itself just flips to 'done' server-side, no persisted plan to invalidate.
  completeTrackItem: async (itemId, addToReview = false) => {
    const result = await apiCompleteTrackItem(itemId, addToReview);
    set(s => ({
      todayItems: s.todayItems.filter(i => i.itemId !== itemId),
      trackItems: Object.fromEntries(Object.entries(s.trackItems).map(([trackId, items]) =>
        [trackId, items.map(i => i.id === itemId ? result : i)])),
    }));
    return result;
  },

  fetchTrackSchedule: async (trackId) => {
    const schedule = await apiFetchTrackSchedule(trackId);
    set(s => ({ trackSchedules: { ...s.trackSchedules, [trackId]: schedule } }));
    return schedule;
  },

  updateSchedule: async (trackId, weekdayBudgets) => {
    const schedule = await apiSaveTrackSchedule(trackId, weekdayBudgets);
    set(s => ({ trackSchedules: { ...s.trackSchedules, [trackId]: schedule } }));
    return schedule;
  },

  // ── Note open ─────────────────────────────────────────────────────────────

  openNote: async (fullPath) => {
    const raw = await fetchNoteContent(fullPath);
    const { frontmatter, body } = splitFrontmatter(raw);
    set({
      currentNoteRaw: raw,
      currentNotePath: fullPath,
      pendingRaw: raw,
      pendingFrontmatter: frontmatter,
      pendingTitle: noteBasename(fullPath),
      isMutable: false,
      centerMode: 'view',
      pendingFiles: {},
    });
    setPendingBlobs({});
    // Suppress unused body — it's stored in pendingRaw; Milkdown reads it via splitFrontmatter
    void body;
  },

  // ── WYSIWYG: live update from Milkdown onChange ───────────────────────────

  // Called by Milkdown's markdownUpdated listener with the serialized body (no frontmatter).
  updatePending: (body) => {
    const { pendingFrontmatter } = get();
    set({ pendingRaw: joinFrontmatter(pendingFrontmatter, body) });
  },

  setPendingTitle: (title) => set({ pendingTitle: title }),

  // ── WYSIWYG: enter/exit edit mode ────────────────────────────────────────

  toggleMutable: () => set(s => ({ isMutable: !s.isMutable })),

  // Cancel edit — discard all pending changes and force Milkdown remount
  cancelEdit: () => {
    const { currentNoteRaw, currentNotePath, tabs, activeTabIndex, pendingFiles } = get();
    // Revoke blob URLs for pasted files that won't be saved
    for (const { blobURL } of Object.values(pendingFiles)) {
      URL.revokeObjectURL(blobURL);
    }
    const updates = {
      pendingRaw: currentNoteRaw,
      pendingTitle: noteBasename(currentNotePath),
      isMutable: false,
      editorResetKey: get().editorResetKey + 1,
      pendingFiles: {},
    };
    setPendingBlobs({});
    // Clear hunks on the active tab so dirty indicator resets
    if (activeTabIndex >= 0 && tabs[activeTabIndex]) {
      const updated = [...tabs];
      updated[activeTabIndex] = { ...updated[activeTabIndex], isMutable: false, hunks: [], pendingFiles: {} };
      updates.tabs = updated;
    }
    set(updates);
  },

  // ── WYSIWYG: sync pendingRaw → disk ──────────────────────────────────────

  syncNote: async () => {
    const { currentNotePath, currentNoteRaw, pendingRaw, pendingTitle, pendingFiles } = get();
    if (!currentNotePath) return;
    try {
      // 1. Upload any pasted files before saving the note content
      const uploadEntries = Object.entries(pendingFiles);
      if (uploadEntries.length > 0) {
        await Promise.all(
          uploadEntries.map(([filename, { file }]) => apiUploadFile(file, filename))
        );
        // Revoke blob URLs now that files are on disk
        for (const { blobURL } of Object.values(pendingFiles)) {
          URL.revokeObjectURL(blobURL);
        }
        set({ pendingFiles: {} });
        setPendingBlobs({});
        // Update active tab snapshot
        const { tabs, activeTabIndex } = get();
        if (activeTabIndex >= 0 && tabs[activeTabIndex]) {
          const updated = [...tabs];
          updated[activeTabIndex] = { ...updated[activeTabIndex], pendingFiles: {} };
          set({ tabs: updated });
        }
      }

      // 2. Rename if title changed
      const currentTitle = noteBasename(currentNotePath);
      let savePath = currentNotePath;
      if (pendingTitle !== currentTitle) {
        const { path: newPath } = await apiRename(currentNotePath, pendingTitle);
        savePath = newPath;
      }

      // 3. Patch content if changed
      const hunks = computeHunks(currentNoteRaw, pendingRaw);
      if (hunks.length > 0) {
        await apiPatch(savePath, hunks);
      }

      set({
        currentNoteRaw: pendingRaw,
        currentNotePath: savePath,
        pendingTitle: noteBasename(savePath),
        isMutable: false,
      });

      // Update the active tab entry to reflect the new path and clear hunks
      const { tabs, activeTabIndex } = get();
      if (activeTabIndex >= 0 && tabs[activeTabIndex]) {
        const updated = [...tabs];
        updated[activeTabIndex] = { ...updated[activeTabIndex], path: savePath, pendingTitle: noteBasename(savePath), isMutable: false, hunks: [], pendingFiles: {} };
        set({ tabs: updated });
      }

      await get().fetchChildrenOf(getParentPath(savePath));
      get().fetchNoteNames();
      get().fetchReviewNotes(get().reviewOffset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) get().sessionExpired();
      else throw e;
    }
  },

  // ── Auth ──────────────────────────────────────────────────────────────────

  checkAuth: async () => {
    let status;
    try {
      status = await checkAuth();
    } catch (e) {
      // Server UNREACHABLE (offline / down) — not a sign-out. Keep the persisted state so
      // the downloaded set stays usable; retry on the next focus/reconnect.
      console.warn('[AUTH] checkAuth: /api/me threw (unreachable) → keeping session', e?.message || e);
      return;
    }
    console.info('[AUTH] checkAuth: /api/me status =', status);
    // Only a real 401/403 means the session is actually gone (e.g. backend restarted →
    // cookie invalid). Anything else non-200 (502/503/504 from a dead proxy, unexpected
    // gateway errors) means "couldn't tell" — treat like unreachable, don't wipe the session.
    if (status === 401 || status === 403) {
      console.warn('[AUTH] checkAuth: real', status, '→ signing out');
      if (get().isAuthenticated) { get().sessionExpired(); return; }
      set({ isAuthenticated: false });
      setPersistedAuth(false);
      return;
    }
    if (status >= 200 && status < 300) {
      set({ isAuthenticated: true });
      setPersistedAuth(true);
      return;
    }
    // Any other status (530 tunnel-down, 5xx, gateway) = "couldn't tell" → keep session.
    console.warn('[AUTH] checkAuth: status', status, 'is NOT 401/403 → keeping session (no sign-out)');
  },

  // Session lost server-side. Wipe all vault data like logout (note content must not
  // linger) but skip the logout endpoint (backend may be gone) and show the login modal.
  // Fixes: after a server restart the app still showed "Sign out" with stale data loaded.
  sessionExpired: () => {
    // Instrumentation: this is the ONLY code path that wipes vault data + pops the login
    // modal from a server response. If you ever get signed out unexpectedly, this stack
    // names the exact caller (which action's 401 handler fired it). A 530/5xx must NEVER
    // reach here — checkAuth and every action gate on status === 401 first.
    console.warn('[AUTH] sessionExpired() — wiping session + showing login. Triggered by:',
      new Error('sessionExpired trigger stack').stack);
    Object.values(get().pendingFiles).forEach(({ blobURL }) => {
      try { URL.revokeObjectURL(blobURL); } catch {}
    });
    setPendingBlobs({});
    localStorage.removeItem(REVIEW_KEY);
    setPersistedAuth(false);
    set(s => ({
      ...initialDataState(),
      isAuthenticated: false,
      showLogin: true,
      editorResetKey: s.editorResetKey + 1,
    }));
  },

  login: async (username, password) => {
    const ok = await apiLogin(username, password);
    if (ok) {
      setPersistedAuth(true);
      set({ isAuthenticated: true, showLogin: false });
      // settings require auth — without this they'd sit at defaults until reload
      await get().loadSettings();
      await get().fetchRootChildren();
      get().fetchNoteNames();
      await get().initReviewSession();
    }
    return ok;
  },

  logout: async () => {
    await apiLogout();
    setPersistedAuth(false);
    // wipe everything loaded from the vault — note content must not survive sign-out
    Object.values(get().pendingFiles).forEach(({ blobURL }) => {
      try { URL.revokeObjectURL(blobURL); } catch {}
    });
    setPendingBlobs({});
    localStorage.removeItem(REVIEW_KEY);
    set(s => ({
      ...initialDataState(),
      isAuthenticated: false,
      editorResetKey: s.editorResetKey + 1,
    }));
  },

  setShowLogin: (v) => set({ showLogin: v }),

  // ── Create ────────────────────────────────────────────────────────────────

  startNewNote: (folderPath) => set({ centerMode: 'new', newNoteFolder: folderPath, newNoteName: '' }),

  cancelNewNote: () => set({ centerMode: 'view', newNoteName: '' }),

  setNewNoteName: (name) => set({ newNoteName: name }),

  createNote: async (folder, name) => {
    try {
      const { path } = await apiCreate(folder, name);
      await get().fetchChildrenOf(folder);
      get().fetchNoteNames();
      await get().openTab(path);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) get().sessionExpired();
      else throw e;
    }
  },

  // ── Delete ────────────────────────────────────────────────────────────────

  deleteNote: async (path) => {
    try {
      await apiDelete(path);
      const parentFolder = getParentPath(path);

      // Remove the deleted note's tab; if it was active, clear the editor
      const { tabs, activeTabIndex } = get();
      const deletedIndex = tabs.findIndex(t => t.path === path);
      const newTabs = tabs.filter(t => t.path !== path);
      const wasActive = deletedIndex === activeTabIndex;

      if (wasActive) {
        set({
          tabs: newTabs,
          activeTabIndex: -1,
          currentNoteRaw: '',
          currentNotePath: null,
          pendingRaw: '',
          pendingFrontmatter: '',
          pendingTitle: '',
          isMutable: false,
          centerMode: 'view',
        });
        // If other tabs remain, activate the nearest one
        if (newTabs.length > 0) {
          const nextIndex = Math.min(deletedIndex, newTabs.length - 1);
          set({ tabs: newTabs, activeTabIndex: nextIndex });
          await get().switchTab(nextIndex);
        }
      } else {
        const newActiveIndex = deletedIndex < activeTabIndex ? activeTabIndex - 1 : activeTabIndex;
        set({ tabs: newTabs, activeTabIndex: newActiveIndex });
      }

      await get().fetchChildrenOf(parentFolder);
      get().fetchNoteNames();
      get().fetchReviewNotes(get().reviewOffset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) get().sessionExpired();
      else throw e;
    }
  },

  // ── Create folder ────────────────────────────────────────────────────────

  createFolder: async (parentPath, name) => {
    try {
      await apiCreateFolder(parentPath, name);
      await get().fetchChildrenOf(parentPath);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) get().sessionExpired();
      else throw e;
    }
  },

  // ── Delete folder (recursive → trash) ──────────────────────────────────────

  deleteFolder: async (path) => {
    try {
      await apiDeleteFolder(path);
      const parentFolder = getParentPath(path);
      const underFolder = p => p === path || p.startsWith(path + '/') || p.startsWith(path + '\\');

      // Close any open tabs for notes that lived inside the deleted folder.
      const { tabs, activeTabIndex } = get();
      const activePath = tabs[activeTabIndex]?.path;
      const newTabs = tabs.filter(t => !underFolder(t.path));

      if (newTabs.length !== tabs.length) {
        if (activePath && underFolder(activePath)) {
          set({
            tabs: newTabs,
            activeTabIndex: -1,
            currentNoteRaw: '',
            currentNotePath: null,
            pendingRaw: '',
            pendingFrontmatter: '',
            pendingTitle: '',
            isMutable: false,
            centerMode: 'view',
          });
          if (newTabs.length > 0) {
            set({ activeTabIndex: 0 });
            await get().switchTab(0);
          }
        } else {
          // Active note survives — recompute its index in the filtered list.
          set({ tabs: newTabs, activeTabIndex: newTabs.findIndex(t => t.path === activePath) });
        }
      }

      await get().fetchChildrenOf(parentFolder);
      get().fetchNoteNames();
      get().fetchReviewNotes(get().reviewOffset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) get().sessionExpired();
      else throw e;
    }
  },

  // ── Move ─────────────────────────────────────────────────────────────────

  moveNote: async (sourcePath, targetFolder) => {
    try {
      const { path: newPath } = await apiMoveNote(sourcePath, targetFolder);

      // Update any open tab that was displaying the moved note
      const { tabs, activeTabIndex } = get();
      const tabIdx = tabs.findIndex(t => t.path === sourcePath);
      if (tabIdx >= 0) {
        const updated = [...tabs];
        updated[tabIdx] = { ...updated[tabIdx], path: newPath };
        const patch = { tabs: updated };
        if (tabIdx === activeTabIndex) patch.currentNotePath = newPath;
        set(patch);
      }

      // Refresh both the source and target folders in the tree
      const sourceParent = getParentPath(sourcePath);
      await Promise.all([
        get().fetchChildrenOf(sourceParent),
        get().fetchChildrenOf(targetFolder),
      ]);
      get().fetchNoteNames();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) get().sessionExpired();
      else throw e;
    }
  },

  // ── Tabs ──────────────────────────────────────────────────────────────────

  // Snapshot current tab's unsaved state into the tabs array.
  _snapshotTab: () => {
    const { tabs, activeTabIndex, currentNoteRaw, pendingRaw, pendingTitle, isMutable, pendingFiles } = get();
    if (activeTabIndex < 0 || !tabs[activeTabIndex]) return;
    const hunks = computeHunks(currentNoteRaw, pendingRaw);
    const updated = [...tabs];
    updated[activeTabIndex] = { ...updated[activeTabIndex], pendingTitle, isMutable, hunks, pendingFiles };
    set({ tabs: updated });
  },

  // Open a note in a tab. If already open, switch to it. Otherwise add new tab.
  openTab: async (fullPath) => {
    const { tabs, activeTabIndex } = get();
    const existingIndex = tabs.findIndex(t => t.path === fullPath);

    if (existingIndex !== -1) {
      if (existingIndex !== activeTabIndex) await get().switchTab(existingIndex);
      return;
    }

    get()._snapshotTab();
    await get().openNote(fullPath);

    const newTab = { path: fullPath, pendingTitle: noteBasename(fullPath), isMutable: false, hunks: [], pendingFiles: {} };
    const newTabs = [...get().tabs, newTab];
    set({ tabs: newTabs, activeTabIndex: newTabs.length - 1 });
  },

  // Switch to tab at index, restoring its pending state.
  switchTab: async (index) => {
    const { tabs, activeTabIndex } = get();
    if (index === activeTabIndex) return;

    get()._snapshotTab();

    const tab = tabs[index];
    if (!tab) return;

    const raw = await fetchNoteContent(tab.path);
    const { frontmatter } = splitFrontmatter(raw);

    let restoredPending = raw;
    if (tab.hunks.length > 0) {
      try { restoredPending = applyHunks(raw, tab.hunks); }
      catch { /* hunks stale — fall back to disk version */ }
    }

    const restoredFiles = tab.pendingFiles ?? {};
    setPendingBlobs(restoredFiles);

    set({
      currentNoteRaw: raw,
      currentNotePath: tab.path,
      pendingRaw: restoredPending,
      pendingFrontmatter: frontmatter,
      pendingTitle: tab.pendingTitle,
      isMutable: tab.isMutable,
      centerMode: 'view',
      activeTabIndex: index,
      pendingFiles: restoredFiles,
    });
  },

  // Close tab at index. If it was active, switch to nearest remaining tab.
  closeTab: async (index) => {
    const { tabs, activeTabIndex } = get();
    // Revoke any pending blob URLs for the tab being closed
    const closingTab = tabs[index];
    if (closingTab?.pendingFiles) {
      for (const { blobURL } of Object.values(closingTab.pendingFiles)) {
        URL.revokeObjectURL(blobURL);
      }
    }
    const isActive = index === activeTabIndex;
    const newTabs = tabs.filter((_, i) => i !== index);

    if (newTabs.length === 0) {
      setPendingBlobs({});
      set({
        tabs: [], activeTabIndex: -1,
        currentNoteRaw: '', currentNotePath: null,
        pendingRaw: '', pendingFrontmatter: '', pendingTitle: '',
        isMutable: false, centerMode: 'view',
        pendingFiles: {},
      });
      return;
    }

    const newActiveIndex = isActive
      ? Math.min(index, newTabs.length - 1)
      : index < activeTabIndex ? activeTabIndex - 1 : activeTabIndex;

    if (isActive) {
      // Invalidate activeTabIndex BEFORE switching: switchTab early-returns when
      // index === activeTabIndex (closing a non-last active tab leaves them equal,
      // which kept showing the closed note), and _snapshotTab must not write the
      // closed tab's state onto whichever tab now occupies that slot.
      set({ tabs: newTabs, activeTabIndex: -1 });
      await get().switchTab(newActiveIndex);
    } else {
      set({ tabs: newTabs, activeTabIndex: newActiveIndex });
    }
  },

  // ── Panel ─────────────────────────────────────────────────────────────────

  toggleLeft:  () => set(s => ({ leftCollapsed:  !s.leftCollapsed })),
  toggleRight: () => set(s => ({ rightCollapsed: !s.rightCollapsed })),

  // ── Settings ──────────────────────────────────────────────────────────────

  loadSettings: async () => {
    try {
      const settings = await apiFetchSettings();
      if (settings && typeof settings === 'object') set({ settings });
    } catch {
      // Non-fatal — defaults remain
    }
  },

  applySettings: async (patch) => {
    const updated = await apiSaveSettings(patch);
    set({ settings: updated });
    // If page size changed, re-fetch the review queue from the top
    if (patch.reviewPageSize != null) {
      await get().fetchReviewNotes(0);
    }
    return updated;
  },
}));

export default useStore;
