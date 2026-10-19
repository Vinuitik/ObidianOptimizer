import { useCallback, useMemo, useState } from 'react';
import useStore from '../../store/useStore';
import { splitFrontmatter } from '../../utils/frontmatter';
import { renderMarkdown } from '../../utils/markdown';
import { resolveWikiTarget } from '../../utils/wikiResolve';
import 'prismjs/themes/prism-tomorrow.css';
import ImageLightbox from './ImageLightbox';

// Read-only markdown renderer (markdown-it via utils/markdown.js) — NOT contentEditable.
// Props: content (raw note incl. frontmatter), resetKey (NoteRenderer parity, unused),
// onOpenNote(target) optional override for wiki-link clicks (default: store openTab).
// W1/W2 hooks: add wikilink / embed rules in utils/markdown.js; click behaviours live in handleClick.
// Note: utils/markdown.js does no HTML sanitising (markdown-it html:true) — same as before.
export default function MarkdownView({ content, onOpenNote, className = '' }) {
  const noteIndex = useStore(s => s.noteIndex);
  const openTab   = useStore(s => s.openTab);
  const [zoom, setZoom] = useState(null);

  const html = useMemo(() => {
    const { body } = splitFrontmatter(content || '');
    // Display-only: trailing blank lines render as dead space above the grade bar.
    return renderMarkdown(body.trimEnd());
  }, [content]);

  const handleClick = useCallback((e) => {
    const img = e.target.closest('img');
    if (img) { // HOOK(W2): embed-specific handling (video/pdf) goes before this
      e.preventDefault();
      setZoom({ src: img.currentSrc || img.src, alt: img.alt || '' });
      return;
    }
    const wiki = e.target.closest('[data-wiki-link]'); // HOOK(W1)
    if (wiki) {
      e.preventDefault();
      const target = wiki.getAttribute('data-wiki-link');
      if (onOpenNote) { onOpenNote(target); return; }
      const fullPath = resolveWikiTarget(noteIndex, target);
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
  }, [noteIndex, openTab, onOpenNote]);

  return (
    <>
      <div
        className={`markdown-body ${className}`.trim()}
        dangerouslySetInnerHTML={{ __html: html }}
        onClick={handleClick}
      />
      {zoom && <ImageLightbox src={zoom.src} alt={zoom.alt} onClose={() => setZoom(null)} />}
    </>
  );
}
