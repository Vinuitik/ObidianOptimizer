import styles from './Card.module.css';

// Glass surface: raised bg, hairline border, 1px top sheen. `interactive` adds hover lift.
function Card({ children, interactive = false, as: Tag = 'div', className = '', ...rest }) {
  return (
    <Tag className={`${styles.card} ${interactive ? styles.interactive : ''} ${className}`} {...rest}>
      {children}
    </Tag>
  );
}

function CardHeader({ title, subtitle, actions, className = '' }) {
  return (
    <div className={`${styles.header} ${className}`}>
      <div className={styles.headText}>
        <h3 className={styles.title}>{title}</h3>
        {subtitle && <p className={styles.subtitle}>{subtitle}</p>}
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </div>
  );
}

Card.Header = CardHeader;
export default Card;
