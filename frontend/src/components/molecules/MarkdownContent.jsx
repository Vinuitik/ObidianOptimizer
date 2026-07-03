import { useCallback } from 'react';
import { renderMarkdown } from '../../utils/markdown';

// Presentational markdown renderer — the ONE place vault markdown becomes HTML with
// all the vault rules (frontmatter table, ![[image]] → <img>, [[wikilink]] → anchor,
// #tags). Extracted from NoteViewer so every surface that shows a note (the reader,
// the ingest review panels, previews) renders identically instead of re-implementing it.
//
// Pass `content` (raw markdown) OR pre-rendered `html` (e.g. the store's cached
// currentNoteHtml). `onOpenNote(target)` fires when a [[wikilink]] is clicked with the
// raw link target — the caller resolves it against its own note index and navigates.
export default function MarkdownContent({ content, html, onOpenNote, className = '' }) {
  const rendered = html ?? (content != null ? renderMarkdown(content) : '');

  const handleClick = useCallback((e) => {
    const wiki = e.target.closest('[data-wiki-link]');
    if (wiki) {
      e.preventDefault();
      onOpenNote?.(wiki.getAttribute('data-wiki-link'));
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
  }, [onOpenNote]);

  return (
    <div
      className={`markdown-body ${className}`.trim()}
      dangerouslySetInnerHTML={{ __html: rendered }}
      onClick={handleClick}
    />
  );
}
