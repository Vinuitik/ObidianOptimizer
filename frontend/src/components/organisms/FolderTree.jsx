import { useState, useEffect } from 'react';
import NavItem from '../molecules/NavItem';
import SearchBar from '../molecules/SearchBar';
import Icon from '../atoms/Icon';
import useStore from '../../store/useStore';
import styles from './FolderTree.module.css';

function sortEntries(entries) {
  return entries.sort(([aName, aNode], [bName, bNode]) => {
    if (aNode.type !== bNode.type) return aNode.type === 'folder' ? -1 : 1;
    return aName.localeCompare(bName);
  });
}

function hasMatch(name, node, query) {
  const q = query.toLowerCase();
  if (node.type === 'file') return name.replace(/\.md$/, '').toLowerCase().includes(q);
  return Object.entries(node.children).some(([n, c]) => hasMatch(n, c, query));
}

const DRAG_TYPE = 'application/obsidian-note';

function TreeNode({ name, node, depth, query, currentNotePath }) {
  const [open, setOpen]               = useState(false);
  const [loading, setLoading]         = useState(false);
  const [dragOver, setDragOver]       = useState(false);
  const [creatingFolder, setCreating] = useState(false);
  const [folderName, setFolderName]   = useState('');

  const openTab         = useStore(s => s.openTab);
  const startNewNote    = useStore(s => s.startNewNote);
  const deleteNote      = useStore(s => s.deleteNote);
  const deleteFolder    = useStore(s => s.deleteFolder);
  const moveNote        = useStore(s => s.moveNote);
  const createFolder    = useStore(s => s.createFolder);
  const fetchChildrenOf = useStore(s => s.fetchChildrenOf);

  // Compute unconditionally — useEffect must be called before any early return
  const isForceOpen = node.type === 'folder' && !!query && hasMatch(name, node, query);
  const isOpen = isForceOpen || open;

  // Must be before ALL conditional returns (rules of hooks)
  useEffect(() => {
    if (isForceOpen && !node.loaded && !loading) {
      setLoading(true);
      fetchChildrenOf(node.fullPath).finally(() => setLoading(false));
    }
  }, [isForceOpen]);

  // ── File node ──────────────────────────────────────────────────────────────
  if (node.type === 'file') {
    const displayName = name.replace(/\.md$/, '');
    if (query && !displayName.toLowerCase().includes(query.toLowerCase())) return null;
    const isActive = currentNotePath === node.fullPath;
    return (
      <div
        draggable
        onDragStart={e => {
          e.dataTransfer.setData(DRAG_TYPE, node.fullPath);
          e.dataTransfer.effectAllowed = 'move';
        }}
      >
        <NavItem
          name={displayName}
          isFolder={false}
          isActive={isActive}
          depth={depth}
          onClick={() => openTab(node.fullPath)}
          onDelete={() => {
            if (window.confirm(`Move "${displayName}" to trash?`)) deleteNote(node.fullPath);
          }}
        />
      </div>
    );
  }

  // ── Folder node ────────────────────────────────────────────────────────────
  if (query && !hasMatch(name, node, query)) return null;

  async function handleToggle() {
    if (!open && !node.loaded && !loading) {
      setLoading(true);
      await fetchChildrenOf(node.fullPath);
      setLoading(false);
    }
    setOpen(o => !o);
  }

  async function handleAddFolder() {
    if (!node.loaded) {
      setLoading(true);
      await fetchChildrenOf(node.fullPath);
      setLoading(false);
    }
    setOpen(true);
    setFolderName('');
    setCreating(true);
  }

  async function submitFolder(e) {
    e.preventDefault();
    const name = folderName.trim();
    setCreating(false);
    if (!name) return;
    await createFolder(node.fullPath, name);
  }

  function handleDragOver(e) {
    // Only accept our own drag type
    if (!e.dataTransfer.types.includes(DRAG_TYPE)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDragOver(true);
  }

  function handleDragLeave(e) {
    // Only clear when leaving the folder row itself, not a child element
    if (!e.currentTarget.contains(e.relatedTarget)) {
      setDragOver(false);
    }
  }

  async function handleDrop(e) {
    e.preventDefault();
    setDragOver(false);
    const sourcePath = e.dataTransfer.getData(DRAG_TYPE);
    if (!sourcePath || sourcePath === node.fullPath) return;
    // Ensure the folder is expanded after the move so the user can see the note
    if (!node.loaded) {
      setLoading(true);
      await fetchChildrenOf(node.fullPath);
      setLoading(false);
    }
    setOpen(true);
    await moveNote(sourcePath, node.fullPath);
  }

  return (
    <div
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      <NavItem
        name={name}
        isFolder
        isOpen={isOpen}
        isDragOver={dragOver}
        depth={depth}
        onClick={handleToggle}
        onAddFolder={handleAddFolder}
        onAdd={() => startNewNote(node.fullPath)}
        onDelete={() => {
          if (window.confirm(`Move folder "${name}" and everything inside it to trash?`)) {
            deleteFolder(node.fullPath);
          }
        }}
      >
        {creatingFolder && (
          <form
            className={styles.folderInput}
            style={{ paddingLeft: (depth + 1) * 14 + 12 }}
            onSubmit={submitFolder}
          >
            <input
              autoFocus
              className={styles.folderInputField}
              value={folderName}
              onChange={e => setFolderName(e.target.value)}
              onBlur={() => setCreating(false)}
              onKeyDown={e => e.key === 'Escape' && setCreating(false)}
              placeholder="folder name"
            />
          </form>
        )}
        {isOpen && loading && (
          <div className={styles.loadingRow} style={{ paddingLeft: (depth + 1) * 14 + 12 }}>
            <span className={styles.loadingDot} />
          </div>
        )}
        {isOpen && !loading && sortEntries(Object.entries(node.children)).map(([childName, childNode]) => (
          <TreeNode
            key={childName}
            name={childName}
            node={childNode}
            depth={depth + 1}
            query={query}
            currentNotePath={currentNotePath}
          />
        ))}
      </NavItem>
    </div>
  );
}

export default function FolderTree() {
  const [query, setQuery]               = useState('');
  const [rootCreating, setRootCreating] = useState(false);
  const [rootFolderName, setRootName]   = useState('');

  const tree            = useStore(s => s.tree);
  const vaultRoot       = useStore(s => s.vaultRoot);
  const currentNotePath = useStore(s => s.currentNotePath);
  const startNewNote    = useStore(s => s.startNewNote);
  const createFolder    = useStore(s => s.createFolder);

  async function submitRootFolder(e) {
    e.preventDefault();
    const name = rootFolderName.trim();
    setRootCreating(false);
    if (!name || !vaultRoot) return;
    await createFolder(vaultRoot, name);
  }

  const rootEntries = sortEntries(Object.entries(tree.children));
  const hasResults  = !query || rootEntries.some(([n, node]) => hasMatch(n, node, query));

  return (
    <div className={styles.tree}>
      <div className={styles.controls}>
        <SearchBar value={query} onChange={setQuery} />
        {vaultRoot && (
          <button className={styles.newRootBtn} onClick={() => startNewNote(vaultRoot)}>
            <Icon name="plus" size={15} color="var(--color-accent-soft)" />
            New note
          </button>
        )}
        {vaultRoot && (
          <button className={styles.newRootBtn} onClick={() => { setRootName(''); setRootCreating(true); }}>
            <Icon name="folder" size={15} color="var(--color-accent-soft)" />
            New folder
          </button>
        )}
        {rootCreating && (
          <form className={styles.folderInput} onSubmit={submitRootFolder}>
            <input
              autoFocus
              className={styles.folderInputField}
              value={rootFolderName}
              onChange={e => setRootName(e.target.value)}
              onBlur={() => setRootCreating(false)}
              onKeyDown={e => e.key === 'Escape' && setRootCreating(false)}
              placeholder="folder name"
            />
          </form>
        )}
      </div>
      {rootEntries.map(([name, node]) => (
        <TreeNode
          key={name}
          name={name}
          node={node}
          depth={0}
          query={query}
          currentNotePath={currentNotePath}
        />
      ))}
      {!hasResults && (
        <p className={styles.noResults}>No results for "{query}"</p>
      )}
    </div>
  );
}
