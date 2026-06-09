import { useRef, useState, useCallback } from 'react';
import useStore from '../../store/useStore';
import PanelHeader from '../molecules/PanelHeader';
import FolderTree from '../organisms/FolderTree';
import ReviewList from '../organisms/ReviewList';
import MilkdownEditor from '../organisms/MilkdownEditor';
import NewNoteForm from '../organisms/NewNoteForm';
import Button from '../atoms/Button';
import Chip from '../atoms/Chip';
import Icon from '../atoms/Icon';
import ReviewRating from '../molecules/ReviewRating';
import styles from './SplitLayout.module.css';

const MIN_PANEL_WIDTH = 160;
const MAX_PANEL_WIDTH = 480;
const DEFAULT_WIDTH = 290;

function noteTitle(fullPath) {
  if (!fullPath) return null;
  return fullPath.split(/[/\\]/).pop().replace(/\.md$/, '');
}

function noteFolderHint(fullPath) {
  if (!fullPath) return null;
  const parts = fullPath.split(/[/\\]/);
  return parts.length >= 2 ? parts[parts.length - 2] : null;
}

function useResize(initialWidth) {
  const [width, setWidth] = useState(initialWidth);
  const dragging = useRef(false);
  const startX = useRef(0);
  const startWidth = useRef(0);

  const onMouseDown = useCallback((e, direction) => {
    dragging.current = true;
    startX.current = e.clientX;
    startWidth.current = width;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';

    const onMouseMove = (e) => {
      if (!dragging.current) return;
      const delta = direction === 'right'
        ? e.clientX - startX.current
        : startX.current - e.clientX;
      const next = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, startWidth.current + delta));
      setWidth(next);
    };

    const onMouseUp = () => {
      dragging.current = false;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };

    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
  }, [width]);

  return [width, onMouseDown];
}

export default function SplitLayout() {
  const leftCollapsed  = useStore(s => s.leftCollapsed);
  const rightCollapsed = useStore(s => s.rightCollapsed);
  const toggleLeft     = useStore(s => s.toggleLeft);
  const toggleRight    = useStore(s => s.toggleRight);
  const currentNotePath = useStore(s => s.currentNotePath);
  const centerMode     = useStore(s => s.centerMode);
  const isMutable      = useStore(s => s.isMutable);
  const toggleMutable  = useStore(s => s.toggleMutable);
  const syncNote       = useStore(s => s.syncNote);
  const deleteNote     = useStore(s => s.deleteNote);
  const reviewNotes    = useStore(s => s.reviewNotes);
  const newNoteName    = useStore(s => s.newNoteName);
  const setNewNoteName = useStore(s => s.setNewNoteName);
  const newNoteFolder  = useStore(s => s.newNoteFolder);
  const createNote     = useStore(s => s.createNote);
  const cancelNewNote  = useStore(s => s.cancelNewNote);
  const pendingTitle   = useStore(s => s.pendingTitle);
  const setPendingTitle = useStore(s => s.setPendingTitle);

  const [leftWidth, onLeftHandleDown] = useResize(DEFAULT_WIDTH);
  const [rightWidth, onRightHandleDown] = useResize(DEFAULT_WIDTH);

  const title = noteTitle(currentNotePath);
  const folderHint = noteFolderHint(currentNotePath);
  const titleIsPlaceholder = centerMode === 'view' && !title;
  const isInReview = reviewNotes.some(n => n.fullPath === currentNotePath);

  const centerTitle = centerMode === 'new'
    ? 'New Note'
    : title ?? 'Select a note';

  const dueSlot = reviewNotes.length > 0
    ? <span className={styles.dueCount}>{reviewNotes.length} due</span>
    : null;

  return (
    <div className={styles.layout}>
      {/* Left — Folder Tree */}
      <div
        className={`${styles.panel} ${styles.panelLeft} ${leftCollapsed ? styles.collapsed : ''}`}
        style={leftCollapsed ? undefined : { width: leftWidth, minWidth: leftWidth }}
      >
        <PanelHeader title="Files" collapsed={leftCollapsed} onToggle={toggleLeft} side="left" />
        <FolderTree />
      </div>

      {/* Left resize handle */}
      {!leftCollapsed && (
        <div
          className={styles.resizeHandle}
          onMouseDown={(e) => onLeftHandleDown(e, 'right')}
        />
      )}

      {/* Center */}
      <div className={styles.center}>
        <div className={styles.centerHeader}>
          <div className={styles.headerLeft}>
            {leftCollapsed && (
              <Button onClick={toggleLeft} variant="ghost">▶ Files</Button>
            )}
            {centerMode === 'new' ? (
              <input
                className={styles.headerTitleInput}
                placeholder="Note name"
                value={newNoteName}
                onChange={e => setNewNoteName(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && newNoteName.trim()) createNote(newNoteFolder, newNoteName.trim());
                  if (e.key === 'Escape') cancelNewNote();
                }}
                autoFocus
              />
            ) : isMutable ? (
              // Editable title input when in edit mode
              <input
                className={styles.headerTitleInput}
                value={pendingTitle}
                onChange={e => setPendingTitle(e.target.value)}
                placeholder="Note name"
              />
            ) : (
              <div className={styles.titleBlock}>
                <div className={titleIsPlaceholder ? styles.centerTitleEmpty : styles.centerTitle}>
                  {centerTitle}
                </div>
                {folderHint && !titleIsPlaceholder && (
                  <div className={styles.centerSubline}>{folderHint} /</div>
                )}
              </div>
            )}
          </div>
          <div className={styles.headerRight}>
            {centerMode !== 'new' && title && (
              <>
                {!isMutable && isInReview && <ReviewRating fullPath={currentNotePath} />}
                {!isMutable && (
                  <Chip>
                    <Icon name="sparkle" size={14} color="var(--color-accent-soft)" />
                    Optimize
                  </Chip>
                )}
                {isMutable ? (
                  <>
                    <Button onClick={syncNote}>Save</Button>
                    <Button variant="ghost" onClick={toggleMutable}>Cancel</Button>
                  </>
                ) : (
                  <>
                    <Chip onClick={toggleMutable}>Edit</Chip>
                    <Button
                      variant="danger"
                      onClick={() => {
                        if (window.confirm(`Move "${title}" to trash?`)) deleteNote(currentNotePath);
                      }}
                    >
                      🗑
                    </Button>
                  </>
                )}
              </>
            )}
            {rightCollapsed && (
              <Button onClick={toggleRight} variant="ghost">Review ◀</Button>
            )}
          </div>
        </div>

        {centerMode === 'new' ? <NewNoteForm /> : <MilkdownEditor />}
      </div>

      {/* Right resize handle */}
      {!rightCollapsed && (
        <div
          className={styles.resizeHandle}
          onMouseDown={(e) => onRightHandleDown(e, 'left')}
        />
      )}

      {/* Right — Review List */}
      <div
        className={`${styles.panel} ${styles.panelRight} ${rightCollapsed ? styles.collapsed : ''}`}
        style={rightCollapsed ? undefined : { width: rightWidth, minWidth: rightWidth }}
      >
        <PanelHeader title="Review" collapsed={rightCollapsed} onToggle={toggleRight} side="right" right={dueSlot} />
        <ReviewList />
      </div>
    </div>
  );
}
