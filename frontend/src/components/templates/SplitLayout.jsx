import { useRef, useState, useCallback, useEffect, useLayoutEffect } from 'react';
import useStore, { selectDirty } from '../../store/useStore';
import PanelHeader from '../molecules/PanelHeader';
import FolderTree from '../organisms/FolderTree';
import ReviewList from '../organisms/ReviewList';
import CmEditor from '../../editor/CmEditor';
import MarkdownView from '../molecules/MarkdownView';
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
  const isMutable      = useStore(s => s.isMutable);
  const toggleMutable  = useStore(s => s.toggleMutable);
  const flushSave      = useStore(s => s.flushSave);
  const commitTitle    = useStore(s => s.commitTitle);
  const deleteNote     = useStore(s => s.deleteNote);
  const reviewNotes    = useStore(s => s.reviewNotes);
  const pendingTitle   = useStore(s => s.pendingTitle);
  const pendingRaw     = useStore(s => s.pendingRaw);
  const saveStatus     = useStore(s => s.saveStatus);
  const titleFocusNonce = useStore(s => s.titleFocusNonce);
  const dirty          = useStore(selectDirty);

  // Title field: local draft, applied (rename) only on blur / Enter.
  const [titleDraft, setTitleDraft] = useState(pendingTitle);
  const titleRef = useRef(null);
  useEffect(() => { setTitleDraft(pendingTitle); }, [pendingTitle, currentNotePath]);
  const submitTitle = () => {
    if (titleDraft.trim() && titleDraft.trim() !== pendingTitle) commitTitle(titleDraft);
    else setTitleDraft(pendingTitle);
  };
  // One line by default; grows to more lines only once a long title wraps.
  useLayoutEffect(() => {
    const el = titleRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }, [titleDraft, isMutable]);
  // New note: focus the title with "Untitled" selected so typing replaces it.
  useEffect(() => {
    if (titleFocusNonce > 0) { titleRef.current?.focus(); titleRef.current?.select(); }
  }, [titleFocusNonce, currentNotePath]);

  const save = useCallback(() => { flushSave().catch(() => {}); }, [flushSave]);

  // Autosave triggers beyond the 1.5 s idle timer (store.updatePending): Mod-s, window blur,
  // tab hidden, page unload (best effort). Mod-e toggles edit <-> reading view.
  useEffect(() => {
    if (!currentNotePath) return undefined;
    const onKey = (e) => {
      if (!(e.metaKey || e.ctrlKey) || e.shiftKey || e.altKey) return;
      const k = e.key.toLowerCase();
      if (k === 's') { e.preventDefault(); save(); }
      else if (k === 'e') { e.preventDefault(); toggleMutable(); }
    };
    const onHidden = () => { if (document.visibilityState === 'hidden') save(); };
    window.addEventListener('keydown', onKey);
    window.addEventListener('blur', save);
    window.addEventListener('beforeunload', save);
    document.addEventListener('visibilitychange', onHidden);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('blur', save);
      window.removeEventListener('beforeunload', save);
      document.removeEventListener('visibilitychange', onHidden);
    };
  }, [currentNotePath, save, toggleMutable]);

  const saveLabel = saveStatus === 'saving' || (isMutable && dirty) ? 'Saving…'
    : isMutable && saveStatus === 'saved' ? 'Saved'
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
  const titleIsPlaceholder = !title;
  const isInReview = reviewNotes.some(n => n.fullPath === currentNotePath);

  // No note open -> the editor pane carries the ONE "Select a note" message; header stays quiet.
  const centerTitle = title ?? '';

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
            {isMutable && title ? (
              <textarea
                ref={titleRef}
                rows={1}
                className={styles.headerTitleInput}
                value={titleDraft}
                aria-label="Note title"
                placeholder="Note name"
                onChange={e => setTitleDraft(e.target.value.replace(/[\r\n]+/g, ' '))}
                onBlur={submitTitle}
                onKeyDown={e => {
                  if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur(); }
                  if (e.key === 'Escape') { setTitleDraft(pendingTitle); e.currentTarget.blur(); }
                }}
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
            {title && (
              <>
                {!isMutable && isInReview && <ReviewRating fullPath={currentNotePath} />}
                {!isMutable && (
                  <Chip className={styles.optimizeChip}>
                    <Icon name="sparkle" size={14} color="var(--accent-text)" />
                    Optimize
                  </Chip>
                )}
                {saveStatus === 'error' && dirty ? (
                  <button type="button" className={`${styles.saveState} ${styles.saveError} ${styles.saveRetry}`} onClick={save}>
                    Save failed — retry
                  </button>
                ) : saveLabel && (
                  <span className={styles.saveState} role="status" aria-live="polite">{saveLabel}</span>
                )}
                <Button variant="secondary" onClick={toggleMutable} title="Toggle edit / reading view (Ctrl+E)">
                  {isMutable ? 'Reading view' : 'Edit'}
                </Button>
                {!isMutable && (
                  <IconButton
                    icon="x"
                    variant="danger"
                    aria-label="Move to trash"
                    onClick={() => {
                      if (window.confirm(`Move "${title}" to trash?`)) deleteNote(currentNotePath);
                    }}
                  />
                )}
              </>
            )}
            {showRight && rightCollapsed && (
              <Button onClick={toggleRight} variant="ghost">Review ◀</Button>
            )}
          </div>
        </div>

        {!isMutable && currentNotePath
          ? <div className={styles.reading}><MarkdownView content={pendingRaw} /></div>
          : <CmEditor />}
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
