import styles from './PageHeader.module.css';

export default function PageHeader({ title, subtitle, actions, className = '' }) {
  return (
    <header className={`${styles.root} ${className}`}>
      <div className={styles.text}>
        <h1 className={styles.title}>{title}</h1>
        {subtitle && <p className={styles.subtitle}>{subtitle}</p>}
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </header>
  );
}
