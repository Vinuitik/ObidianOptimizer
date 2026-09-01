import { useEffect, useMemo, useState, useRef } from 'react';
import { parseSourceRegion } from '../../utils/inboxParse';
import { pdfPageUrl } from '../../utils/noteMedia';   // shared so offline warm caches identical keys
import RsvpReader from '../molecules/RsvpReader';
import NoteRenderer from '../molecules/NoteRenderer';
import styles from './SourceSplicePanel.module.css';

// Left panel of the review: the source, spliced to the region THIS note came from
// (INGESTION_V2_FLOWS §7). Granularity is what the note's `## Source` footer records —
// video → play from the start timestamp; pdf → jump to the referenced page; text → the
// prose itself, readable fast via RSVP. (Precise bbox/char crops need the backend to
// serve locator.SpliceView; page/timestamp is the reuse-only approximation for now.)
export default function SourceSplicePanel({ item, onOrientation }) {
  const region = useMemo(
    () => parseSourceRegion(item?.content || '', item?.source),
    [item],
  );
  // Prefer the locally-downloaded copy: its extension decides the viewer (a YouTube note
  // with a downloaded .mp4 plays as local video, not an external iframe). Falls back to the
  // external ref when there's no local copy yet.
  const kind = item ? sourceKind(region.local || region.ref, item) : null;

  // Text sources default to RSVP (fast read); toggle to a rendered read view so the
  // user can eyeball the note themselves. Sticky across notes.
  const [textMode, setTextMode] = useState('rsvp');   // 'rsvp' | 'read'

  // Report split orientation so the review layout matches the source shape (landscape
  // video → wide → horizontal split; portrait/other → vertical). YouTube is ~always
  // landscape; a local <video> reports its real pixel size on loadedmetadata; else null.
  useEffect(() => {
    if (!onOrientation) return;
    if (kind === 'youtube') onOrientation('landscape');
    else if (kind !== 'video') onOrientation(null);
    // 'video' resolves from the element's loadedmetadata below.
  }, [kind, onOrientation]);

  if (!item) return <div className={styles.empty}>Select a note to see its source.</div>;
  return (
    <div className={styles.panel}>
      <div className={styles.head}>
        <span className={styles.kind}>{kind}</span>
        {region.ref && (
          <a className={styles.ref} href={hrefFor(region.ref)} target="_blank" rel="noreferrer" title={region.ref}>
            ↗ {shorten(region.ref)}
          </a>
        )}
        {region.startSeconds != null && <span className={styles.at}>{fmtRange(region.startSeconds, region.endSeconds)}</span>}
        {region.pages.length > 0 && <span className={styles.at}>{pageRange(region.pages)}</span>}
        {kind === 'text' && (
          <div className={styles.toggle}>
            <button className={`${styles.toggleBtn} ${textMode === 'rsvp' ? styles.toggleOn : ''}`}
                    onClick={() => setTextMode('rsvp')}>RSVP</button>
            <button className={`${styles.toggleBtn} ${textMode === 'read' ? styles.toggleOn : ''}`}
                    onClick={() => setTextMode('read')}>Read</button>
          </div>
        )}
      </div>
      <div className={styles.viewer}>
        <Viewer kind={kind} region={region} item={item} textMode={textMode} onOrientation={onOrientation} />
      </div>
    </div>
  );
}

function Viewer({ kind, region, item, textMode, onOrientation }) {
  if (kind === 'youtube') {
    const id = youtubeId(region.ref);
    const start = region.startSeconds || 0;
    if (id) {
      return (
        <iframe
          className={styles.frame}
          src={`https://www.youtube-nocookie.com/embed/${id}?start=${start}`}
          title="source video" allowFullScreen
        />
      );
    }
  }
  if (kind === 'video') {
    return <ClipVideo src={mediaUrl(region.local || region.ref)}
                      start={region.startSeconds} end={region.endSeconds}
                      onOrientation={onOrientation} />;
  }
  if (kind === 'pdf') {
    const vp = vaultPdfPath(region.local || region.ref);
    // Local vault PDF → render the actual referenced page(s) inline (so a note mis-sourced
    // from the TOC is obvious), with a toggle-able highlight. External http PDF → plain embed.
    if (vp) return <PdfPages path={vp} pages={region.pages} bboxes={region.bboxes} />;
    const src = mediaUrl(region.local || region.ref);
    return <embed key={src} className={styles.media} type="application/pdf"
                  src={`${src}#page=${region.pages[0] || 1}`} />;
  }
  if (kind === 'web') {
    // Instagram/TikTok reels are best-effort yt-dlp downloads (login walls/rate limits — see
    // embedder/ingest/router.py). No local copy landed → the platform itself blocks third-party
    // iframing (X-Frame-Options/CSP), so the embed renders blank. Link out instead of a dead frame.
    if (isEmbedBlockedHost(region.ref)) {
      return (
        <div className={styles.blocked}>
          <p>Couldn&rsquo;t fetch this source for offline viewing.</p>
          <a className={styles.blockedBtn} href={hrefFor(region.ref)} target="_blank" rel="noreferrer">
            Open original ↗
          </a>
        </div>
      );
    }
    return <iframe className={styles.frame} src={region.ref} title="source page" />;
  }
  // text / unknown → default RSVP (fast read); 'read' renders the note so the user can
  // scan it themselves (read-only Milkdown, the real renderer).
  if (textMode === 'read') {
    return <div className={styles.readbox}><NoteRenderer content={item.content} resetKey={item.path} /></div>;
  }
  return <RsvpReader text={item.content} />;
}

// A video bounded to [start, end] — the note's own span. We DON'T trust the `#t=start,end`
// media-fragment to seek: browsers honor it inconsistently (Chrome often ignores the start),
// so the clip would open at 0:00 and play the whole file — exactly the "not a defined range"
// bug. Instead we set `currentTime = start` explicitly on loadedmetadata. The timeupdate guard
// pauses ONCE at end (fragment end isn't enforced either); replay from the end runs free.
function ClipVideo({ src, start, end, onOrientation }) {
  const released = useRef(false);
  const seeked = useRef(false);
  useEffect(() => { released.current = false; seeked.current = false; }, [src, start, end]);
  const onMeta = (e) => {
    onOrientation?.(e.target.videoHeight > e.target.videoWidth ? 'portrait' : 'landscape');
    if (start != null && !seeked.current) {
      try { e.target.currentTime = start; } catch {}   // explicit seek — reliable across browsers
      seeked.current = true;
    }
  };
  const onTime = (e) => {
    if (end != null && !released.current && e.target.currentTime >= end) {
      e.target.pause();
      released.current = true;   // clip shown once; don't re-pause on manual replay
    }
  };
  return <video key={src} className={styles.media} src={src} controls preload="metadata"
                onLoadedMetadata={onMeta} onTimeUpdate={onTime} />;
}

// The referenced PDF page(s), rasterized server-side by the embedder (/pdf-page) so we see
// exactly what the note claims as its source — not the clunky whole-doc <embed>. When the
// note carries a sub-page bbox (v2 ingest), we CROP to just that region by default (crop=1) so a
// chapter that starts/ends mid-page isn't drowned in the rest of the page. "Full page" unchecks
// the crop → the whole page with the region highlighted (for context / when the crop is wrong).
// v1 notes have no bbox → the clean page(s), which already exposes TOC/agenda mis-sourcing.
function PdfPages({ path, pages, bboxes }) {
  const list = pages && pages.length ? pages : [1];
  const hasBoxes = bboxes && list.some(p => bboxes[p]);
  const [cropRegion, setCropRegion] = useState(true);   // default: show ONLY the sourced region
  const [fullDoc, setFullDoc] = useState(false);   // escape hatch: scroll the whole PDF
  const [failed, setFailed] = useState(false);
  const raw = mediaUrl(path);

  return (
    <div className={styles.pdfWrap}>
      <div className={styles.pdfControls}>
        {hasBoxes && !fullDoc && (
          <label className={styles.pdfToggle}>
            <input type="checkbox" checked={cropRegion} onChange={e => setCropRegion(e.target.checked)} />
            Crop to region
          </label>
        )}
        {/* v2 can clip a unit mid-page; this lets the user scroll the whole doc to find the
            spill-over / preceding context the page crop dropped. */}
        <button type="button" className={styles.pdfBtn} onClick={() => setFullDoc(f => !f)}>
          {fullDoc ? '↩ Note page' + (list.length > 1 ? 's' : '') : '⤢ Full PDF'}
        </button>
        <a className={styles.pdfBtn} href={`${raw}#page=${list[0]}`} target="_blank" rel="noreferrer">Open ↗</a>
      </div>

      {fullDoc ? (
        <embed className={styles.pdfEmbed} type="application/pdf" src={`${raw}#page=${list[0]}`} />
      ) : failed ? (
        <div className={styles.pdfMissing}>
          <p>Couldn’t render {list.length > 1 ? 'these pages' : 'this page'}.</p>
          <button type="button" className={styles.pdfBtn} onClick={() => setFullDoc(true)}>⤢ View full PDF</button>
        </div>
      ) : (
        <div className={styles.pdfPages}>
          {list.map(p => (
            <img key={`${p}-${cropRegion}`} className={styles.pdfPage} loading="lazy"
                 alt={`page ${p}`} src={pdfPageUrl(path, p, bboxes?.[p] || null, cropRegion)}
                 onError={() => setFailed(true)} />
          ))}
        </div>
      )}
    </div>
  );
}

// pdfPageUrl is imported from utils/noteMedia (shared with the offline warm so keys match).

// A local vault PDF path the /pdf-page endpoint can resolve (vault-relative, .pdf), or null
// for an external http PDF (which the endpoint can't open — caller falls back to <embed>).
function vaultPdfPath(ref) {
  const r = (ref || '').trim();
  if (!r || /^https?:\/\//i.test(r)) return null;
  return /\.pdf$/i.test(r) ? r.replace(/^\/+/, '') : null;
}

// ── helpers ──────────────────────────────────────────────────────────────────
function sourceKind(ref, item) {
  const r = (ref || '').toLowerCase();
  if (/youtube\.com|youtu\.be/.test(r)) return 'youtube';
  if (/\.(mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac)(\?|#|$)/.test(r)) return 'video';
  if (/\.pdf(\?|#|$)/.test(r)) return 'pdf';
  if (/^https?:\/\//.test(r)) return 'web';
  if ((item?.source || '').trim() === '' || /text/i.test(item?.sourceType || '')) return 'text';
  return 'text';
}

function youtubeId(url) {
  const m = /(?:v=|youtu\.be\/|embed\/)([\w-]{11})/.exec(url || '');
  return m ? m[1] : null;
}

// http(s) refs load directly; vault-relative media is served by nginx (range-request
// support): resources/… → /vault-media/…, _workspace/… → /workspace/…. Legacy bare image
// names still go through the Java image endpoint.
// Paths are percent-encoded per segment: real media filenames carry spaces and non-ASCII
// (e.g. the fullwidth colon "：" in yt-dlp titles), which are invalid raw in a src attribute.
function mediaUrl(ref) {
  if (/^https?:\/\//.test(ref || '')) return ref;
  const p = (ref || '').replace(/^\/+/, '');
  const enc = (rest) => rest.split('/').map(encodeURIComponent).join('/');
  if (p.startsWith('_workspace/')) return '/workspace/' + enc(p.slice('_workspace/'.length));
  if (p.startsWith('resources/'))  return '/vault-media/' + enc(p.slice('resources/'.length));
  return `/api/images/${encodeURIComponent(ref || '')}`;
}
function hrefFor(ref) { return /^https?:\/\//.test(ref) ? ref : mediaUrl(ref); }

// Platforms whose pages refuse third-party iframing (X-Frame-Options/CSP) — same list as
// router.py's VIDEO_HOST_RE, since these are exactly the yt-dlp best-effort hosts.
function isEmbedBlockedHost(ref) { return /instagram\.com|tiktok\.com/i.test(ref || ''); }

function fmt(s) {
  const m = Math.floor(s / 60), sec = String(s % 60).padStart(2, '0');
  return `${m}:${sec}`;
}
// A literal span, always two numbers: [start – end]. If we somehow have no end
// (legacy start-only anchor) show [start – ?] rather than silently hiding the range.
function fmtRange(start, end) {
  return end != null ? `[${fmt(start)} – ${fmt(end)}]` : `[${fmt(start)} – ?]`;
}
// Pages as a compact range too, not a comma list: [4–5] or [7] for a single page.
function pageRange(pages) {
  const lo = Math.min(...pages), hi = Math.max(...pages);
  return lo === hi ? `[${lo}]` : `[${lo}–${hi}]`;
}
function shorten(ref) {
  try { return new URL(ref).host; } catch { return ref.split(/[/\\]/).pop(); }
}
