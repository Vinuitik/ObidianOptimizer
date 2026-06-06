import Button from '../atoms/Button';
import styles from './PanelHeader.module.css';

export default function PanelHeader({ title, collapsed, onToggle, side, right }) {
  const arrow = collapsed
    ? (side === 'left' ? '▶' : '◀')
    : (side === 'left' ? '◀' : '▶');

  return (
    <div className={styles.header}>
      <span className={styles.title}>{title}</span>
      {right && <span className={styles.right}>{right}</span>}
      <Button onClick={onToggle} variant="ghost">{arrow}</Button>
    </div>
  );
}
