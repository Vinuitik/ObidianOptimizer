import { useCallback } from 'react';
import { MilkdownProvider } from '@milkdown/react';
import useStore from '../../store/useStore';
import { splitFrontmatter } from '../../utils/frontmatter';
import FrontmatterTable from './FrontmatterTable';
import EditorErrorBoundary from '../organisms/EditorErrorBoundary';
import { MilkdownEditorInner } from '../organisms/MilkdownEditor';
import styles from '../organisms/MilkdownEditor.module.css';

const noop = () => {};

// Read-only rich note renderer — the SAME Milkdown pipeline the main editor uses
// (GFM tables, obsidian-image `![[…]]`, math, prism code highlighting, wiki-links), so
// every surface that DISPLAYS a note (review, ingest inbox preview) renders identically
// to the main page instead of a weaker markdown-it pass. This is the proper shared
// renderer: reuses MilkdownEditorInner in non-editable mode. Pass raw note markdown
// (frontmatter included) as `content`; `resetKey` forces a remount when the note changes.
export default function NoteRenderer({ content, resetKey = 0 }) {
  const noteIndex = useStore(s => s.noteIndex);
  const openTab   = useStore(s => s.openTab);

  const { frontmatter, body } = splitFrontmatter(content || '');
  // Trailing blank lines at the very END of a note render as dead empty space in the read
  // view — invisible before, but obvious now that the review grade bar sits right below.
  // Trim trailing whitespace/newlines, end only. Display-only: the stored note is untouched.
  const trimmedBody = body.trimEnd();

  // Read-only click delegation: [[wikilinks]] open the target note; external links open
  // in a new tab. Mirrors MilkdownEditor's non-editable handleClick.
  const handleClick = useCallback((e) => {
    const wiki = e.target.closest('[data-wiki-link]');
    if (wiki) {
      e.preventDefault();
      const target = wiki.getAttribute('data-wiki-link');
      const basename = target.split(/[/\\]/).pop().toLowerCase();
      const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
      if (fullPath) openTab?.(fullPath);
      return;
    }
    const link = e.target.closest('a[href]');
    if (link) {
      const href = link.getAttribute('href');
      if (href && !href.startsWith('#')) {
        e.preventDefault();
        window.open(href, '_blank', 'noopener,noreferrer');
      }
    }
  }, [noteIndex, openTab]);

  return (
    <div className={styles.wrapper} onClick={handleClick}>
      <FrontmatterTable frontmatter={frontmatter} />
      <div className={styles.milkdownWrapper}>
        <EditorErrorBoundary>
          <MilkdownProvider key={resetKey}>
            <MilkdownEditorInner
              body={trimmedBody}
              isMutable={false}
              onBodyChange={noop}
              onFilePaste={noop}
              onUnsupported={noop}
            />
          </MilkdownProvider>
        </EditorErrorBoundary>
      </div>
    </div>
  );
}
