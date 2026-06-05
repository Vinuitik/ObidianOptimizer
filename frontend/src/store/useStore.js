import { create } from 'zustand';
import {
  ApiError,
  fetchNames, fetchReview, fetchNoteContent,
  checkAuth, login as apiLogin, logout as apiLogout,
  createNote as apiCreate, updateNote as apiUpdate,
  renameNote as apiRename, deleteNote as apiDelete,
} from '../api/notes';
import { renderMarkdown } from '../utils/markdown';

// ── Tree building ────────────────────────────────────────────────────────────

function buildTree(paths) {
  const root = { type: 'folder', children: {}, fullPath: '' };
  if (!paths.length) return root;

  const splitPaths = paths.map(p => p.split(/[/\\]/).filter(Boolean));
  const minLen = Math.min(...splitPaths.map(p => p.length));
  let prefixLen = 0;
  for (let i = 0; i < minLen - 1; i++) {
    if (splitPaths.every(p => p[i] === splitPaths[0][i])) prefixLen = i + 1;
    else break;
  }

  const sep = paths[0].includes('/') ? '/' : '\\';
  const rootFullPath = splitPaths[0].slice(0, prefixLen).join(sep);
  root.fullPath = rootFullPath;

  paths.forEach((fullPath, idx) => {
    const parts = splitPaths[idx].slice(prefixLen);
    let node = root;
    let currentPath = rootFullPath;

    parts.forEach((part, i) => {
      currentPath = currentPath + sep + part;
      if (i === parts.length - 1) {
        node.children[part] = { type: 'file', fullPath };
      } else {
        if (!node.children[part]) {
          node.children[part] = { type: 'folder', children: {}, fullPath: currentPath };
        }
        node = node.children[part];
      }
    });
  });

  return root;
}

function buildReviewList(paths) {
  const used = {};
  const map = new Map();
  paths.forEach(fullPath => {
    let base = fullPath.split(/[/\\]/).pop().replace(/\.md$/, '');
    let unique = base;
    let count = used[base] || 0;
    while (map.has(unique)) { count += 1; unique = `${base} (${count})`; }
    used[base] = count;
    map.set(unique, fullPath);
  });
  return Array.from(map.entries()).map(([shortName, fullPath]) => ({ shortName, fullPath }));
}

// ── Store ────────────────────────────────────────────────────────────────────

const useStore = create((set, get) => ({
  // Vault data
  tree: { type: 'folder', children: {}, fullPath: '' },
  vaultRoot: '',
  reviewNotes: [],

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

  // Panel collapse (UI state)
  leftCollapsed: false,
  rightCollapsed: false,

  // ── Data fetching ──────────────────────────────────────────────────────────

  fetchNoteNames: async () => {
    try {
      const paths = await fetchNames();
      const tree = buildTree(paths);
      set({ tree, vaultRoot: tree.fullPath });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        set({ tree: { type: 'folder', children: {}, fullPath: '' }, vaultRoot: '' });
      } else throw e;
    }
  },

  fetchReviewNotes: async () => {
    try {
      const paths = await fetchReview();
      set({ reviewNotes: buildReviewList(paths) });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        set({ reviewNotes: [] });
      } else throw e;
    }
  },

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
      await get().fetchNoteNames();
      await get().fetchReviewNotes();
    }
    return ok;
  },

  logout: async () => {
    await apiLogout();
    set({ isAuthenticated: false });
  },

  setShowLogin: (v) => set({ showLogin: v }),

  // ── Create ────────────────────────────────────────────────────────────────

  startNewNote: (folderPath) => set({ centerMode: 'new', newNoteFolder: folderPath }),

  cancelNewNote: () => set({ centerMode: 'view' }),

  createNote: async (folder, name) => {
    try {
      const { path } = await apiCreate(folder, name);
      await get().fetchNoteNames();
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
    const { currentNotePath } = get();
    try {
      const currentTitle = currentNotePath?.split(/[/\\]/).pop().replace(/\.md$/, '') ?? '';
      let savePath = currentNotePath;

      if (title !== currentTitle) {
        const { path: newPath } = await apiRename(currentNotePath, title);
        savePath = newPath;
      }

      await apiUpdate(savePath, content);

      const html = renderMarkdown(content);
      set({ currentNoteHtml: html, currentNoteRaw: content, currentNotePath: savePath, centerMode: 'view' });

      // Refresh tree (rename changes the key) and review list
      await get().fetchNoteNames();
      await get().fetchReviewNotes();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) set({ showLogin: true });
      else throw e;
    }
  },

  // ── Delete ────────────────────────────────────────────────────────────────

  deleteNote: async (path) => {
    try {
      await apiDelete(path);
      set({ currentNoteHtml: '', currentNoteRaw: '', currentNotePath: null, centerMode: 'view' });
      await get().fetchNoteNames();
      await get().fetchReviewNotes();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) set({ showLogin: true });
      else throw e;
    }
  },

  // ── Panel ─────────────────────────────────────────────────────────────────

  toggleLeft: () => set(s => ({ leftCollapsed: !s.leftCollapsed })),
  toggleRight: () => set(s => ({ rightCollapsed: !s.rightCollapsed })),
}));

export default useStore;
