import Icon from '../atoms/Icon';
import Button from '../atoms/Button';
import styles from './EmptyState.module.css';

// One line of why + ONE primary action. action = { label, onClick }.
export default function EmptyState({ icon = 'inbox', title, description, action, className = '' }) {
  return (
    <div className={`${styles.root} ${className}`}>
      <span className={styles.icon}><Icon name={icon} size={28} /></span>
      <h3 className={styles.title}>{title}</h3>
      {description && <p className={styles.desc}>{description}</p>}
      {action && <Button variant="primary" onClick={action.onClick}>{action.label}</Button>}
    </div>
  );
}
