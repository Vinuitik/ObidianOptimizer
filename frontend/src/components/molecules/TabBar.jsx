import useStore from '../../store/useStore';
import styles from './TabBar.module.css';

export default function TabBar() {
  const tabs           = useStore(s => s.tabs);
  const activeTabIndex = useStore(s => s.activeTabIndex);
  const switchTab      = useStore(s => s.switchTab);
  const closeTab       = useStore(s => s.closeTab);

  if (tabs.length === 0) return null;

  return (
    <div className={styles.tabBar}>
      {tabs.map((tab, i) => {
        const isActive = i === activeTabIndex;
        const isDirty  = tab.hunks.length > 0;
        return (
          <button
            key={tab.path}
            className={`${styles.tab} ${isActive ? styles.active : ''}`}
            onClick={() => switchTab(i)}
          >
            <span className={styles.title}>
              {tab.pendingTitle || tab.path.split(/[/\\]/).pop().replace(/\.md$/, '')}
            </span>
            {isDirty && <span className={styles.dirty} title="Unsaved changes">•</span>}
            <span
              className={styles.close}
              role="button"
              aria-label="Close tab"
              onClick={e => { e.stopPropagation(); closeTab(i); }}
            >
              ×
            </span>
          </button>
        );
      })}
    </div>
  );
}
