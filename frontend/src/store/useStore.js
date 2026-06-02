import { create } from 'zustand';
import { fetchNames, fetchReview, fetchNoteContent } from '../api/notes';
import { renderMarkdown } from '../utils/markdown';

function buildTree(paths) {
  const root = { type: 'folder', children: {} };
  if (!paths.length) return root;

  // Find common prefix to strip (e.g. C:\Users\...\NewLife)
  const splitPaths = paths.map(p => p.split(/[/\\]/).filter(Boolean));
  let prefixLen = 0;
  const minLen = Math.min(...splitPaths.map(p => p.length));
  for (let i = 0; i < minLen - 1; i++) {
    if (splitPaths.every(p => p[i] === splitPaths[0][i])) prefixLen = i + 1;
    else break;
  }

  paths.forEach((fullPath, idx) => {
    const parts = splitPaths[idx].slice(prefixLen);
    let node = root;
    parts.forEach((part, i) => {
      if (i === parts.length - 1) {
        node.children[part] = { type: 'file', fullPath };
      } else {
        if (!node.children[part]) node.children[part] = { type: 'folder', children: {} };
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

const useStore = create((set) => ({
  tree: { type: 'folder', children: {} },
  reviewNotes: [],
  currentNoteHtml: '',
  currentNotePath: null,
  leftCollapsed: false,
  rightCollapsed: false,

  fetchNoteNames: async () => {
    const paths = await fetchNames();
    set({ tree: buildTree(paths) });
  },

  fetchReviewNotes: async () => {
    const paths = await fetchReview();
    set({ reviewNotes: buildReviewList(paths) });
  },

  openNote: async (fullPath) => {
    const content = await fetchNoteContent(fullPath);
    set({ currentNoteHtml: renderMarkdown(content), currentNotePath: fullPath });
  },

  toggleLeft: () => set(s => ({ leftCollapsed: !s.leftCollapsed })),
  toggleRight: () => set(s => ({ rightCollapsed: !s.rightCollapsed })),
}));

export default useStore;
