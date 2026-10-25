import { parseSourceRegion } from './inboxParse';

// What kind of source an inbox note came from: 'youtube' | 'video' | 'pdf' | 'web' | 'text'.
// text -> the note IS the source (no compare panel). Prefers the local copy's extension.
export function sourceKind(ref, item) {
  const r = (ref || '').toLowerCase();
  if (/youtube\.com|youtu\.be/.test(r)) return 'youtube';
  if (/\.(mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac)(\?|#|$)/.test(r)) return 'video';
  if (/\.pdf(\?|#|$)/.test(r)) return 'pdf';
  if (/^https?:\/\//.test(r)) return 'web';
  return 'text';
}

export function itemSourceKind(item) {
  if (!item) return null;
  const region = parseSourceRegion(item.content || '', item.source);
  return sourceKind(region.local || region.ref, item);
}
