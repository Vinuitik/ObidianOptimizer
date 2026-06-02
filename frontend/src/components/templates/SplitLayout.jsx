import useStore from '../../store/useStore';
import PanelHeader from '../molecules/PanelHeader';
import FolderTree from '../organisms/FolderTree';
import ReviewList from '../organisms/ReviewList';
import NoteViewer from '../organisms/NoteViewer';
import NewNoteForm from '../organisms/NewNoteForm';
import NoteEditor from '../organisms/NoteEditor';
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
  const centerMode = useStore(s => s.centerMode);
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const startEdit = useStore(s => s.startEdit);
  const logout = useStore(s => s.logout);
  const setShowLogin = useStore(s => s.setShowLogin);

  const title = noteTitle(currentNotePath);

  const centerTitle = {
    new: 'New Note',
    edit: 'Editing',
    view: title ?? 'Select a note',
  }[centerMode];

  const titleIsPlaceholder = centerMode === 'view' && !title;

  return (
    <div className={styles.layout}>
      {/* Left — Folder Tree */}
      <div className={`${styles.panel} ${styles.panelLeft} ${leftCollapsed ? styles.collapsed : ''}`}>
        <PanelHeader title="Files" collapsed={leftCollapsed} onToggle={toggleLeft} side="left" />
        <FolderTree />
      </div>

      {/* Center — dynamic mode */}
      <div className={styles.center}>
        <div className={styles.centerHeader}>
          <div className={styles.headerLeft}>
            {leftCollapsed && (
              <Button onClick={toggleLeft} variant="ghost">▶ Files</Button>
            )}
            <span className={titleIsPlaceholder
              ? `${styles.centerTitle} ${styles.centerTitleEmpty}`
              : styles.centerTitle}
            >
              {centerTitle}
            </span>
            {centerMode === 'view' && title && (
              <Button onClick={startEdit} variant="ghost">Edit</Button>
            )}
          </div>
          <div className={styles.headerRight}>
            <Button
              variant="ghost"
              onClick={isAuthenticated ? logout : () => setShowLogin(true)}
              title={isAuthenticated ? 'Signed in — click to sign out' : 'Sign in to edit'}
            >
              {isAuthenticated ? 'Sign out' : 'Sign in'}
            </Button>
            {rightCollapsed && (
              <Button onClick={toggleRight} variant="ghost">Review ◀</Button>
            )}
          </div>
        </div>

        {centerMode === 'new' && <NewNoteForm />}
        {centerMode === 'edit' && <NoteEditor />}
        {centerMode === 'view' && <NoteViewer />}
      </div>

      {/* Right — Review List */}
      <div className={`${styles.panel} ${styles.panelRight} ${rightCollapsed ? styles.collapsed : ''}`}>
        <PanelHeader title="Review" collapsed={rightCollapsed} onToggle={toggleRight} side="right" />
        <ReviewList />
      </div>
    </div>
  );
}
