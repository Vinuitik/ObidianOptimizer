import { create } from 'zustand';
import {
  ApiError,
  fetchNames, fetchChildren, fetchReview, fetchNoteContent,
  checkAuth, login as apiLogin, logout as apiLogout,
  createNote as apiCreate, patchNote as apiPatch,
  renameNote as apiRename, deleteNote as apiDelete,
} from '../api/notes';
import { renderMarkdown } from '../utils/markdown';
import { computeHunks } from '../utils/diff';

// ── Review session (localStorage) ────────────────────────────────────────────

const REVIEW_KEY = 'obsOpt_reviewSession';

function getReviewSession() {
  const today = new Date().toISOString().slice(0, 10);
  try {
    const stored = JSON.parse(localStorage.getItem(REVIEW_KEY) || 'null');
    if (stored?.date === today) return { offset: stored.offset ?? 0 };
  } catch {}
  // New day or corrupt data — reset
  localStorage.setItem(REVIEW_KEY, JSON.stringify({ date: today, offset: 0 }));
  return { offset: 0 };
}

function saveReviewOffset(offset) {
  const today = new Date().toISOString().slice(0, 10);
  localStorage.setItem(REVIEW_KEY, JSON.stringify({ date: today, offset }));
}

// ── Tree helpers ──────────────────────────────────────────────────────────────

function getParentPath(fullPath) {
  const sep = fullPath.includes('\\') ? '\\' : '/';
  return fullPath.split(/[/\\]/).slice(0, -1).join(sep);
}

// Deep-merge: replace children of the node at folderPath, mark it loaded.
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

// Build a children map from the /children API response.
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

// noteIndex entries from a list of file full-paths.
function indexEntries(filePaths) {
  return filePaths.map(fp => [
    fp.split(/[/\\]/).pop().replace(/\.md$/i, '').toLowerCase(),
    fp,
  ]);
}

// ── Review list builder ───────────────────────────────────────────────────────

function buildReviewList(paths) {
  const used = {};
  const map = new Map();
  paths.forEach(fullPath => {
    let base   = fullPath.split(/[/\\]/).pop().replace(/\.md$/, '');
    let unique = base;
    let count  = used[base] || 0;
    while (map.has(unique)) { count += 1; unique = `${base} (${count})`; }
    used[base] = count;
    map.set(unique, fullPath);
  });
  return Array.from(map.entries()).map(([shortName, fullPath]) => ({ shortName, fullPath }));
}

// ── Store ─────────────────────────────────────────────────────────────────────

const useStore = create((set, get) => ({
  // Vault tree (lazily populated)
  tree: { type: 'folder', children: {}, fullPath: '', loaded: false },
  vaultRoot: '',
  noteIndex: new Map(),

  // Current note
  currentNoteHtml: '',
  currentNoteRaw: '',
  currentNotePath: null,

  // Auth
  isAuthenticated: false,
  showLogin: false,

  // Center panel mode: 'view' | 'new' | 'edit'
  centerMode: 'view',
  newNoteFolder: null,
  newNoteName: '',

  // Review
  reviewNotes: [],
  reviewOffset: 0,
  reviewHasMore: false,

  // Panel collapse
  leftCollapsed: false,
  rightCollapsed: false,

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

  // ── Full noteIndex rebuild (background, for wiki-link resolution) ─────────

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

  // ── Review: init with localStorage offset (call on app load) ─────────────

  initReviewSession: async () => {
    const { offset } = getReviewSession();
    await get().fetchReviewNotes(offset);
  },

  // ── Review: fetch a page ──────────────────────────────────────────────────

  fetchReviewNotes: async (offset = 0) => {
    try {
      const { notes, hasMore } = await fetchReview(offset, 40);
      set({ reviewNotes: buildReviewList(notes), reviewOffset: offset, reviewHasMore: hasMore });
      saveReviewOffset(offset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        set({ reviewNotes: [], reviewHasMore: false });
      } else throw e;
    }
  },

  // ── Review: load next page (only when current batch is empty) ────────────

  loadMoreReview: async () => {
    const { reviewOffset } = get();
    await get().fetchReviewNotes(reviewOffset + 40);
  },

  // ── Review: dismiss a note after rating ──────────────────────────────────

  dismissFromReview: (fullPath) => {
    set(s => ({ reviewNotes: s.reviewNotes.filter(n => n.fullPath !== fullPath) }));
  },

  // ── Note open ─────────────────────────────────────────────────────────────

  openNote: async (fullPath) => {
    const raw = await fetchNoteContent(fullPath);
    console.log('[READ in  200]', raw.slice(0, 200));
    const html = renderMarkdown(raw);
    console.log('[READ out 200]', html.slice(0, 200));
    set({ currentNoteHtml: html, currentNoteRaw: raw, currentNotePath: fullPath, centerMode: 'view' });
  },

  // ── Auth ──────────────────────────────────────────────────────────────────

  checkAuth: async () => {
    const ok = await checkAuth();
    set({ isAuthenticated: ok });
  },

  login: async (username, password) => {
    const ok = await apiLogin(username, password);
    if (ok) {
      set({ isAuthenticated: true, showLogin: false });
      await get().fetchRootChildren();
      get().fetchNoteNames();
      await get().initReviewSession();
    }
    return ok;
  },

  logout: async () => {
    await apiLogout();
    set({ isAuthenticated: false });
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
      await get().openNote(path);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) set({ showLogin: true });
      else throw e;
    }
  },

  // ── Edit ──────────────────────────────────────────────────────────────────

  startEdit: () => set({ centerMode: 'edit' }),

  cancelEdit: () => set({ centerMode: 'view' }),

  saveNote: async (title, content) => {
    const { currentNotePath, currentNoteRaw } = get();
    try {
      const currentTitle = currentNotePath?.split(/[/\\]/).pop().replace(/\.md$/, '') ?? '';
      let savePath = currentNotePath;

      if (title !== currentTitle) {
        const { path: newPath } = await apiRename(currentNotePath, title);
        savePath = newPath;
      }

      const hunks = computeHunks(currentNoteRaw, content);
      if (hunks.length > 0) {
        await apiPatch(savePath, hunks);
      }

      const html = renderMarkdown(content);
      set({ currentNoteHtml: html, currentNoteRaw: content, currentNotePath: savePath, centerMode: 'view' });

      await get().fetchChildrenOf(getParentPath(savePath));
      get().fetchNoteNames();
      get().fetchReviewNotes(get().reviewOffset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) set({ showLogin: true });
      else throw e;
    }
  },

  // ── Delete ────────────────────────────────────────────────────────────────

  deleteNote: async (path) => {
    try {
      await apiDelete(path);
      const parentFolder = getParentPath(path);
      set({ currentNoteHtml: '', currentNoteRaw: '', currentNotePath: null, centerMode: 'view' });
      await get().fetchChildrenOf(parentFolder);
      get().fetchNoteNames();
      get().fetchReviewNotes(get().reviewOffset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) set({ showLogin: true });
      else throw e;
    }
  },

  // ── Panel ─────────────────────────────────────────────────────────────────

  toggleLeft:  () => set(s => ({ leftCollapsed:  !s.leftCollapsed })),
  toggleRight: () => set(s => ({ rightCollapsed: !s.rightCollapsed })),
}));

export default useStore;
