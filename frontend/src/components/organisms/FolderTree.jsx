import { useState } from 'react';
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

function TreeNode({ name, node, depth, query, currentNotePath }) {
  const [open, setOpen] = useState(false);
  const openNote = useStore(s => s.openNote);
  const startNewNote = useStore(s => s.startNewNote);
  const deleteNote = useStore(s => s.deleteNote);

  if (node.type === 'file') {
    const displayName = name.replace(/\.md$/, '');
    if (query && !displayName.toLowerCase().includes(query.toLowerCase())) return null;
    const isActive = currentNotePath === node.fullPath;
    return (
      <NavItem
        name={displayName}
        isFolder={false}
        isActive={isActive}
        depth={depth}
        onClick={() => openNote(node.fullPath)}
        onDelete={() => {
          if (window.confirm(`Move "${displayName}" to trash?`)) deleteNote(node.fullPath);
        }}
      />
    );
  }

  if (query && !hasMatch(name, node, query)) return null;

  // Auto-expand folders that contain query matches
  const isOpen = (!!query && hasMatch(name, node, query)) || open;

  return (
    <NavItem
      name={name}
      isFolder
      isOpen={isOpen}
      depth={depth}
      onClick={() => setOpen(o => !o)}
      onAdd={() => startNewNote(node.fullPath)}
    >
      {isOpen && sortEntries(Object.entries(node.children)).map(([childName, childNode]) => (
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
  );
}

export default function FolderTree() {
  const [query, setQuery] = useState('');
  const tree = useStore(s => s.tree);
  const vaultRoot = useStore(s => s.vaultRoot);
  const currentNotePath = useStore(s => s.currentNotePath);
  const startNewNote = useStore(s => s.startNewNote);

  const rootEntries = sortEntries(Object.entries(tree.children));
  const hasResults = !query || rootEntries.some(([n, node]) => hasMatch(n, node, query));

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
