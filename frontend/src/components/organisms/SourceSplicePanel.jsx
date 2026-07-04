import { useEffect, useMemo, useState, useRef } from 'react';
import { parseSourceRegion } from '../../utils/inboxParse';
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
        {region.startSeconds != null && <span className={styles.at}>from {fmt(region.startSeconds)}</span>}
        {region.pages.length > 0 && <span className={styles.at}>p. {region.pages.join(', ')}</span>}
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
    const page = region.pages[0] || 1;
    const src = mediaUrl(region.local || region.ref);
    return <embed key={src} className={styles.media} type="application/pdf"
                  src={`${src}#page=${page}`} />;
  }
  if (kind === 'web') {
    return <iframe className={styles.frame} src={region.ref} title="source page" />;
  }
  // text / unknown → default RSVP (fast read); 'read' renders the note so the user can
  // scan it themselves (read-only Milkdown, the real renderer).
  if (textMode === 'read') {
    return <div className={styles.readbox}><NoteRenderer content={item.content} resetKey={item.path} /></div>;
  }
  return <RsvpReader text={item.content} />;
}

// A video bounded to [start, end] — the note's own span. `#t=start,end` seeks to start;
// the timeupdate guard pauses ONCE at end (media-fragment end isn't reliably enforced by
// browsers). If the user hits play again from the end, we stop fighting them and let it run.
function ClipVideo({ src, start, end, onOrientation }) {
  const released = useRef(false);
  const frag = start != null ? `#t=${start}${end != null ? ',' + end : ''}` : '';
  const full = src + frag;
  useEffect(() => { released.current = false; }, [full]);   // reset the clip guard per note
  const onMeta = (e) => onOrientation?.(
    e.target.videoHeight > e.target.videoWidth ? 'portrait' : 'landscape');
  const onTime = (e) => {
    if (end != null && !released.current && e.target.currentTime >= end) {
      e.target.pause();
      released.current = true;   // clip shown once; don't re-pause on manual replay
    }
  };
  return <video key={full} className={styles.media} src={full} controls
                onLoadedMetadata={onMeta} onTimeUpdate={onTime} />;
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
function mediaUrl(ref) {
  if (/^https?:\/\//.test(ref || '')) return ref;
  const p = (ref || '').replace(/^\/+/, '');
  if (p.startsWith('_workspace/')) return '/workspace/' + p.slice('_workspace/'.length);
  if (p.startsWith('resources/'))  return '/vault-media/' + p.slice('resources/'.length);
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
