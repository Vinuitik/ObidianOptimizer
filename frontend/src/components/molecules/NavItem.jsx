import styles from './NavItem.module.css';

export default function NavItem({ name, isFolder, isOpen, depth, onClick, children }) {
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
      </div>
      {children}
    </div>
  );
}
