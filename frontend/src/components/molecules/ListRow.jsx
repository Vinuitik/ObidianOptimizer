import styles from './ListRow.module.css';

// Standard row: leading (icon/checkbox), title + meta, trailing (badge/actions).
// Pass onClick to make the whole row a button; `active` highlights the selection.
export default function ListRow({ leading, title, meta, trailing, onClick, active = false, className = '' }) {
  const body = (
    <>
      {leading && <span className={styles.leading}>{leading}</span>}
      <span className={styles.text}>
        <span className={styles.title}>{title}</span>
        {meta && <span className={styles.meta}>{meta}</span>}
      </span>
      {trailing && <span className={styles.trailing}>{trailing}</span>}
    </>
  );
  const cls = `${styles.row} ${active ? styles.active : ''} ${className}`;
  return onClick ? (
    <button type="button" className={`${cls} ${styles.clickable}`} onClick={onClick}>{body}</button>
  ) : (
    <div className={cls}>{body}</div>
  );
}
