import { useEffect, useMemo } from 'react';
import { parseSourceRegion } from '../../utils/inboxParse';
import RsvpReader from '../molecules/RsvpReader';
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
  const kind = item ? sourceKind(region.ref, item) : null;

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
        {region.startSeconds != null && <span className={styles.at}>from {fmt(region.startSeconds)}</span>}
        {region.pages.length > 0 && <span className={styles.at}>p. {region.pages.join(', ')}</span>}
      </div>
      <div className={styles.viewer}>
        <Viewer kind={kind} region={region} item={item} onOrientation={onOrientation} />
      </div>
    </div>
  );
}

function Viewer({ kind, region, item, onOrientation }) {
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
    const src = mediaUrl(region.ref) + (region.startSeconds ? `#t=${region.startSeconds}` : '');
    const onMeta = (e) => onOrientation?.(
      e.target.videoHeight > e.target.videoWidth ? 'portrait' : 'landscape');
    return <video key={src} className={styles.media} src={src} controls onLoadedMetadata={onMeta} />;
  }
  if (kind === 'pdf') {
    const page = region.pages[0] || 1;
    return <embed key={region.ref} className={styles.media} type="application/pdf"
                  src={`${mediaUrl(region.ref)}#page=${page}`} />;
  }
  if (kind === 'web') {
    return <iframe className={styles.frame} src={region.ref} title="source page" />;
  }
  // text / unknown → read the note's prose fast; this IS the relevant region for text.
  return <RsvpReader text={item.content} />;
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

// http(s) refs load directly; vault-relative resources go through the media endpoint.
function mediaUrl(ref) {
  if (/^https?:\/\//.test(ref || '')) return ref;
  return `/api/images/${encodeURIComponent(ref || '')}`;
}
function hrefFor(ref) { return /^https?:\/\//.test(ref) ? ref : mediaUrl(ref); }

function fmt(s) {
  const m = Math.floor(s / 60), sec = String(s % 60).padStart(2, '0');
  return `${m}:${sec}`;
}
function shorten(ref) {
  try { return new URL(ref).host; } catch { return ref.split(/[/\\]/).pop(); }
}
