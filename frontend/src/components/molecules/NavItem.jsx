import styles from './NavItem.module.css';

export default function NavItem({ name, isFolder, isOpen, depth, onClick, onAdd, children }) {
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
      </div>
      {children}
    </div>
  );
}
