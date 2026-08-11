import { useCallback, useState } from 'react';
import { renderMarkdown } from '../../utils/markdown';
import ImageLightbox from './ImageLightbox';

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
  const [zoom, setZoom] = useState(null); // { src, alt } while an image is open fullscreen

  const handleClick = useCallback((e) => {
    // Embedded image → open the zoom lightbox. Standalone PWAs disable browser pinch-zoom,
    // so this is the only way to enlarge the (max-width:100%) inline images.
    const img = e.target.closest('img');
    if (img) {
      e.preventDefault();
      setZoom({ src: img.currentSrc || img.src, alt: img.alt || '' });
      return;
    }
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
    <>
      <div
        className={`markdown-body ${className}`.trim()}
        dangerouslySetInnerHTML={{ __html: rendered }}
        onClick={handleClick}
      />
      {zoom && <ImageLightbox src={zoom.src} alt={zoom.alt} onClose={() => setZoom(null)} />}
    </>
  );
}
