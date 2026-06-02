import useStore from '../../store/useStore';
import PanelHeader from '../molecules/PanelHeader';
import FolderTree from '../organisms/FolderTree';
import ReviewList from '../organisms/ReviewList';
import NoteViewer from '../organisms/NoteViewer';
import Button from '../atoms/Button';
import styles from './SplitLayout.module.css';

function noteTitle(fullPath) {
  if (!fullPath) return null;
  return fullPath.split(/[/\\]/).pop().replace(/\.md$/, '');
}

export default function SplitLayout() {
  const leftCollapsed = useStore(s => s.leftCollapsed);
  const rightCollapsed = useStore(s => s.rightCollapsed);
  const toggleLeft = useStore(s => s.toggleLeft);
  const toggleRight = useStore(s => s.toggleRight);
  const currentNotePath = useStore(s => s.currentNotePath);

  const title = noteTitle(currentNotePath);

  return (
    <div className={styles.layout}>
      {/* Left — Folder Tree */}
      <div className={`${styles.panel} ${styles.panelLeft} ${leftCollapsed ? styles.collapsed : ''}`}>
        <PanelHeader title="Files" collapsed={leftCollapsed} onToggle={toggleLeft} side="left" />
        <FolderTree />
      </div>

      {/* Center — Note Viewer */}
      <div className={styles.center}>
        <div className={styles.centerHeader}>
          {leftCollapsed && (
            <Button onClick={toggleLeft} variant="ghost">▶ Files</Button>
          )}
          <span className={title ? styles.centerTitle : `${styles.centerTitle} ${styles.centerTitleEmpty}`}>
            {title ?? 'Select a note'}
          </span>
          {rightCollapsed && (
            <Button onClick={toggleRight} variant="ghost">Review ◀</Button>
          )}
        </div>
        <NoteViewer />
      </div>

      {/* Right — Review List */}
      <div className={`${styles.panel} ${styles.panelRight} ${rightCollapsed ? styles.collapsed : ''}`}>
        <PanelHeader title="Review" collapsed={rightCollapsed} onToggle={toggleRight} side="right" />
        <ReviewList />
      </div>
    </div>
  );
}
