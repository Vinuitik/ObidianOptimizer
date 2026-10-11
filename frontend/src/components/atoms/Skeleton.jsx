import styles from './Skeleton.module.css';

// <Skeleton lines={3} /> for text blocks, <Skeleton height={120} /> for a rect.
export default function Skeleton({ lines, width = '100%', height = 14, className = '' }) {
  if (lines) {
    return (
      <div className={`${styles.stack} ${className}`} role="status" aria-label="Loading" aria-busy="true">
        {Array.from({ length: lines }, (_, i) => (
          <span
            key={i}
            className={styles.bar}
            style={{ height, width: i === lines - 1 && lines > 1 ? '60%' : width }}
          />
        ))}
      </div>
    );
  }
  return (
    <span
      className={`${styles.bar} ${className}`}
      style={{ height, width }}
      role="status"
      aria-label="Loading"
      aria-busy="true"
    />
  );
}
