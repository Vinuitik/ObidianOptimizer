import styles from './NavItem.module.css';

export default function NavItem({ name, isFolder, isOpen, depth, onClick, onAdd, onDelete, children }) {
  return (
    <div style={{ paddingLeft: depth * 16 }}>
      <div className={styles.row}>
        <span className={styles.icon}>
          {isFolder ? (isOpen ? '▾' : '▸') : ''}
        </span>
        <span
          className={`${styles.label} ${isFolder ? styles.folder : styles.file}`}
          onClick={onClick}
        >
          {name}
        </span>
        {onAdd && (
          <button
            className={styles.addBtn}
            onClick={e => { e.stopPropagation(); onAdd(); }}
            title="New note in this folder"
          >
            +
          </button>
        )}
        {onDelete && (
          <button
            className={styles.deleteBtn}
            onClick={e => { e.stopPropagation(); onDelete(); }}
            title="Move to trash"
          >
            🗑
          </button>
        )}
      </div>
      {children}
    </div>
  );
}
