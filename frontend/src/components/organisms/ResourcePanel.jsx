import styles from './ResourcePanel.module.css';

const RESOURCE_TYPES = [
  { value: 'pdf',   label: 'PDF' },
  { value: 'image', label: 'Image' },
  { value: 'video', label: 'Video' },
  { value: 'audio', label: 'Audio' },
];

export default function ResourcePanel({ resourceType, onResourceTypeChange }) {
  return (
    <div className={styles.panel}>
      <div className={styles.header}>
        <span className={styles.headerTitle}>Resource</span>
        <select
          className={styles.typeSelect}
          value={resourceType}
          onChange={e => onResourceTypeChange(e.target.value)}
          title="Resource type"
        >
          {RESOURCE_TYPES.map(t => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </select>
      </div>

      <div className={styles.placeholder}>
        <div className={styles.placeholderIcon}>
          {resourceType === 'pdf'   && '📄'}
          {resourceType === 'image' && '🖼'}
          {resourceType === 'video' && '🎬'}
          {resourceType === 'audio' && '🎵'}
        </div>
        <p className={styles.placeholderText}>
          Resource loading coming soon
        </p>
        <span className={styles.badge}>{resourceType.toUpperCase()}</span>
      </div>
    </div>
  );
}
