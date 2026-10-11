import { useRef, useState, useCallback, useEffect } from 'react';
import useStore from '../../store/useStore';
import PanelHeader from '../molecules/PanelHeader';
import FolderTree from '../organisms/FolderTree';
import ReviewList from '../organisms/ReviewList';
import MilkdownEditor from '../organisms/MilkdownEditor';
import NewNoteForm from '../organisms/NewNoteForm';
import Button from '../atoms/Button';
import Chip from '../atoms/Chip';
import IconButton from '../atoms/IconButton';
import Icon from '../atoms/Icon';
import ReviewRating from '../molecules/ReviewRating';
import TabBar from '../molecules/TabBar';
import { useIsMobile } from '../../utils/useMediaQuery';
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

// Unsaved = body, title or pasted files differ from what is on disk.
function selectDirty(s) {
  if (!s.currentNotePath || !s.isMutable) return false;
  const base = s.currentNotePath.split(/[/\\]/).pop().replace(/\.md$/, '');
  return s.pendingRaw !== s.currentNoteRaw
    || s.pendingTitle !== base
    || Object.keys(s.pendingFiles ?? {}).length > 0;
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
  // One switch (settings.flashcardsEnabled) picks the review SYSTEM: flashcards ON →
  // review lives on the dedicated /review page, so the main-page review list is not
  // shown; OFF → the review list occupies the right panel here. See ReviewPage / NavBar.
  const flashcardsEnabled = useStore(s => s.settings.flashcardsEnabled ?? true);
  const showRight      = !flashcardsEnabled;
  const currentNotePath = useStore(s => s.currentNotePath);
  const centerMode     = useStore(s => s.centerMode);
  const isMutable      = useStore(s => s.isMutable);
  const toggleMutable  = useStore(s => s.toggleMutable);
  const cancelEdit     = useStore(s => s.cancelEdit);
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
  const showToast      = useStore(s => s.showToast);
  const dirty          = useStore(selectDirty);

  // 'saving' | 'saved' | 'error' | null — visible save state next to the Save button
  const [saveStatus, setSaveStatus] = useState(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);

  const handleSave = useCallback(async () => {
    setSaveStatus('saving');
    setConfirmDiscard(false);
    try {
      await syncNote();
      setSaveStatus('saved');
    } catch (e) {
      setSaveStatus('error');
      showToast({ message: `Couldn't save — ${e?.message ?? e}. Your changes are still here.`, tone: 'error' });
    }
  }, [syncNote, showToast]);

  const handleCancel = () => {
    if (dirty) setConfirmDiscard(true);
    else cancelEdit();
  };

  // Cmd/Ctrl+S saves while editing (and swallows the browser's "save page").
  useEffect(() => {
    if (!isMutable) return undefined;
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 's') {
        e.preventDefault();
        handleSave();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isMutable, handleSave]);

  useEffect(() => {
    if (saveStatus !== 'saved') return undefined;
    const t = setTimeout(() => setSaveStatus(null), 2500);
    return () => clearTimeout(t);
  }, [saveStatus]);

  // Switching note / leaving edit mode drops any open discard prompt.
  useEffect(() => { setConfirmDiscard(false); }, [currentNotePath, isMutable]);
  useEffect(() => { if (dirty && saveStatus === 'error') setSaveStatus(null); }, [dirty]); // eslint-disable-line react-hooks/exhaustive-deps

  const saveLabel = saveStatus === 'saving' ? 'Saving…'
    : saveStatus === 'error' && isMutable ? 'Save failed'
    : isMutable ? (dirty ? 'Unsaved changes' : 'No changes')
    : saveStatus === 'saved' ? 'Saved'
    : null;

  const [leftWidth, onLeftHandleDown] = useResize(DEFAULT_WIDTH);
  const [rightWidth, onRightHandleDown] = useResize(DEFAULT_WIDTH);

  // On mobile the side panels float over the editor as drawers; a scrim behind
  // the open drawer dismisses it on tap-outside (native-feeling, and the only
  // way to close it besides the header chevron).
  const isMobile = useIsMobile();
  const drawerOpen = isMobile && (!leftCollapsed || (showRight && !rightCollapsed));
  const closeDrawers = () => {
    if (!leftCollapsed) toggleLeft();
    if (showRight && !rightCollapsed) toggleRight();
  };

  // Picking a note from the Files drawer should reveal the note, not leave the drawer
  // covering it (desktop keeps the panel open — it sits beside the editor).
  useEffect(() => {
    if (isMobile && currentNotePath && !useStore.getState().leftCollapsed) toggleLeft();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentNotePath]);

  const title = noteTitle(currentNotePath);
  const folderHint = noteFolderHint(currentNotePath);
  const titleIsPlaceholder = centerMode === 'view' && !title;
  const isInReview = reviewNotes.some(n => n.fullPath === currentNotePath);

  // No note open -> the editor pane carries the ONE "Select a note" message; header stays quiet.
  const centerTitle = centerMode === 'new' ? 'New Note' : title ?? '';

  const dueSlot = reviewNotes.length > 0
    ? <span className={styles.dueCount}>{reviewNotes.length} due</span>
    : null;

  return (
    <div className={styles.layout}>
      {drawerOpen && (
        <div className={styles.scrim} onClick={closeDrawers} aria-hidden="true" />
      )}

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
        <TabBar />
        <div className={styles.centerHeader}>
          {/* Left edge — collapse button only */}
          <div className={styles.headerEdge}>
            {leftCollapsed && (
              <Button onClick={toggleLeft} variant="secondary" icon="folder" className={styles.filesBtn}>Files</Button>
            )}
          </div>

          {/* Center — title, always visually centered */}
          <div className={styles.headerCenter}>
            {centerMode === 'new' ? (
              <textarea
                className={styles.headerTitleInput}
                placeholder="Note name"
                value={newNoteName}
                rows={1}
                onChange={e => setNewNoteName(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey && newNoteName.trim()) {
                    e.preventDefault();
                    createNote(newNoteFolder, newNoteName.trim());
                  }
                  if (e.key === 'Escape') cancelNewNote();
                }}
                autoFocus
              />
            ) : isMutable ? (
              <textarea
                className={styles.headerTitleInput}
                value={pendingTitle}
                rows={1}
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

          {/* Right edge — action buttons */}
          <div className={`${styles.headerEdge} ${styles.headerEdgeRight}`}>
            {centerMode !== 'new' && title && (
              <>
                {!isMutable && isInReview && <ReviewRating fullPath={currentNotePath} />}
                {!isMutable && (
                  <Chip className={styles.optimizeChip}>
                    <Icon name="sparkle" size={14} color="var(--color-accent-soft)" />
                    Optimize
                  </Chip>
                )}
                {saveLabel && (
                  <span
                    className={`${styles.saveState} ${saveStatus === 'error' && isMutable ? styles.saveError : ''}`}
                    role="status"
                    aria-live="polite"
                  >
                    {saveLabel}
                  </span>
                )}
                {isMutable ? (
                  confirmDiscard ? (
                    <div className={styles.discardPrompt} role="alertdialog" aria-label="Discard unsaved changes">
                      <span>Discard unsaved changes?</span>
                      <Button variant="danger" size="sm" onClick={() => { setConfirmDiscard(false); cancelEdit(); }}>Discard</Button>
                      <Button variant="ghost" size="sm" onClick={() => setConfirmDiscard(false)}>Keep editing</Button>
                    </div>
                  ) : (
                    <>
                      <Button variant="primary" onClick={handleSave} loading={saveStatus === 'saving'}>Save</Button>
                      <Button variant="ghost" onClick={handleCancel}>Cancel</Button>
                    </>
                  )
                ) : (
                  <>
                    <Button variant="secondary" onClick={toggleMutable}>Edit</Button>
                    <IconButton
                      icon="x"
                      variant="danger"
                      aria-label="Move to trash"
                      onClick={() => {
                        if (window.confirm(`Move "${title}" to trash?`)) deleteNote(currentNotePath);
                      }}
                    />
                  </>
                )}
              </>
            )}
            {showRight && rightCollapsed && (
              <Button onClick={toggleRight} variant="ghost">Review ◀</Button>
            )}
          </div>
        </div>

        {centerMode === 'new' ? <NewNoteForm /> : <MilkdownEditor />}
      </div>

      {/* Right resize handle */}
      {showRight && !rightCollapsed && (
        <div
          className={styles.resizeHandle}
          onMouseDown={(e) => onRightHandleDown(e, 'left')}
        />
      )}

      {/* Right — Review List (hidden in flashcard mode) */}
      {showRight && (
        <div
          className={`${styles.panel} ${styles.panelRight} ${rightCollapsed ? styles.collapsed : ''}`}
          style={rightCollapsed ? undefined : { width: rightWidth, minWidth: rightWidth }}
        >
          <PanelHeader title="Review" collapsed={rightCollapsed} onToggle={toggleRight} side="right" right={dueSlot} />
          <ReviewList />
        </div>
      )}
    </div>
  );
}
