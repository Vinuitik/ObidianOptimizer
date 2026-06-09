import { create } from 'zustand';
import {
  ApiError,
  fetchNames, fetchChildren, fetchReview, fetchNoteContent,
  checkAuth, login as apiLogin, logout as apiLogout,
  createNote as apiCreate, patchNote as apiPatch,
  renameNote as apiRename, deleteNote as apiDelete,
} from '../api/notes';
import { computeHunks } from '../utils/diff';
import { splitFrontmatter, joinFrontmatter } from '../utils/frontmatter';

// ── Review session (localStorage) ────────────────────────────────────────────

const REVIEW_KEY = 'obsOpt_reviewSession';

function getReviewSession() {
  const today = new Date().toISOString().slice(0, 10);
  try {
    const stored = JSON.parse(localStorage.getItem(REVIEW_KEY) || 'null');
    if (stored?.date === today) return { offset: stored.offset ?? 0 };
  } catch {}
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

function noteBasename(fullPath) {
  return fullPath?.split(/[/\\]/).pop().replace(/\.md$/, '') ?? '';
}

// ── Store ─────────────────────────────────────────────────────────────────────

const useStore = create((set, get) => ({
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

  // Auth
  isAuthenticated: false,
  showLogin: false,

  // Center panel mode: 'view' | 'new'
  // 'edit' is now conveyed by isMutable; centerMode only distinguishes new-note form
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

  loadMoreReview: async () => {
    const { reviewOffset } = get();
    await get().fetchReviewNotes(reviewOffset + 40);
  },

  dismissFromReview: (fullPath) => {
    set(s => ({ reviewNotes: s.reviewNotes.filter(n => n.fullPath !== fullPath) }));
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
    });
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

  // ── WYSIWYG: sync pendingRaw → disk ──────────────────────────────────────

  syncNote: async () => {
    const { currentNotePath, currentNoteRaw, pendingRaw, pendingTitle } = get();
    if (!currentNotePath) return;
    try {
      const currentTitle = noteBasename(currentNotePath);
      let savePath = currentNotePath;

      if (pendingTitle !== currentTitle) {
        const { path: newPath } = await apiRename(currentNotePath, pendingTitle);
        savePath = newPath;
      }

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

      await get().fetchChildrenOf(getParentPath(savePath));
      get().fetchNoteNames();
      get().fetchReviewNotes(get().reviewOffset);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) set({ showLogin: true });
      else throw e;
    }
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

  // ── Delete ────────────────────────────────────────────────────────────────

  deleteNote: async (path) => {
    try {
      await apiDelete(path);
      const parentFolder = getParentPath(path);
      set({
        currentNoteRaw: '',
        currentNotePath: null,
        pendingRaw: '',
        pendingFrontmatter: '',
        pendingTitle: '',
        isMutable: false,
        centerMode: 'view',
      });
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
