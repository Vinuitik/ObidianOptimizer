import { useState } from 'react';
import NavItem from '../molecules/NavItem';
import useStore from '../../store/useStore';
import styles from './FolderTree.module.css';

function sortEntries(entries) {
  return entries.sort(([aName, aNode], [bName, bNode]) => {
    if (aNode.type !== bNode.type) return aNode.type === 'folder' ? -1 : 1;
    return aName.localeCompare(bName);
  });
}

function TreeNode({ name, node, depth }) {
  const [open, setOpen] = useState(false);
  const openNote = useStore(s => s.openNote);
  const startNewNote = useStore(s => s.startNewNote);

  if (node.type === 'file') {
    return (
      <NavItem
        name={name.replace(/\.md$/, '')}
        isFolder={false}
        depth={depth}
        onClick={() => openNote(node.fullPath)}
      />
    );
  }

  return (
    <NavItem
      name={name}
      isFolder
      isOpen={open}
      depth={depth}
      onClick={() => setOpen(o => !o)}
      onAdd={() => startNewNote(node.fullPath)}
    >
      {open && sortEntries(Object.entries(node.children)).map(([childName, childNode]) => (
        <TreeNode key={childName} name={childName} node={childNode} depth={depth + 1} />
      ))}
    </NavItem>
  );
}

export default function FolderTree() {
  const tree = useStore(s => s.tree);
  const vaultRoot = useStore(s => s.vaultRoot);
  const startNewNote = useStore(s => s.startNewNote);

  return (
    <div className={styles.tree}>
      {vaultRoot && (
        <button className={styles.newRootBtn} onClick={() => startNewNote(vaultRoot)}>
          + New note
        </button>
      )}
      {sortEntries(Object.entries(tree.children)).map(([name, node]) => (
        <TreeNode key={name} name={name} node={node} depth={0} />
      ))}
    </div>
  );
}
