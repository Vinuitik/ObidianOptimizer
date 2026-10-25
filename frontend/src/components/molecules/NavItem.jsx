import Icon from '../atoms/Icon';
import styles from './NavItem.module.css';

export default function NavItem({ name, isFolder, isOpen, isActive, isAI, isDragOver, depth, onClick, onAdd, onAddFolder, onDelete, children }) {
  return (
    <div style={{ paddingLeft: depth * 14 }}>
      <div className={`${styles.row} ${isActive ? styles.active : ''} ${isDragOver ? styles.dragOver : ''}`} onClick={onClick}>
        {isActive && <span className={styles.accentBar} />}
        {isFolder && (
          <span className={`${styles.chevron} ${isOpen ? styles.chevronOpen : ''}`}>
            <Icon name="chevron" size={13} strokeWidth={2} color="var(--text-3)" />
          </span>
        )}
        <Icon
          name={isFolder ? 'folder' : 'file'}
          size={15}
          color={
            isFolder
              ? (isOpen ? 'var(--accent-text)' : 'var(--text-2)')
              : (isActive ? 'var(--accent-text)' : 'var(--text-3)')
          }
        />
        <span className={`${styles.label} ${isFolder ? styles.folder : styles.file} ${isActive ? styles.fileActive : ''}`}>
          {name}
        </span>
        {isAI && <Icon name="sparkle" size={13} color="var(--accent-text)" />}
        {onAddFolder && (
          <button
            className={styles.addBtn}
            onClick={e => { e.stopPropagation(); onAddFolder(); }}
            title="New subfolder"
          >
            <Icon name="folder" size={12} color="currentColor" />
          </button>
        )}
        {onAdd && (
          <button
            className={styles.addBtn}
            onClick={e => { e.stopPropagation(); onAdd(); }}
            title="New note in this folder"
            aria-label="New note in this folder"
          >
            <Icon name="plus" size={13} />
          </button>
        )}
        {onDelete && (
          <button
            className={styles.deleteBtn}
            onClick={e => { e.stopPropagation(); onDelete(); }}
            title="Move to bin"
            aria-label="Move to bin"
          >
            <Icon name="x" size={13} />
          </button>
        )}
      </div>
      {children}
    </div>
  );
}
