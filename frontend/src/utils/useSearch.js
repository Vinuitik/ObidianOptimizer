import { useState, useEffect, useRef } from 'react';
import { searchNotes, ApiError } from '../api/notes';
import { offlineKeywordSearch } from './offlineSearch';

// Server unreachable → fall back to offline keyword search instead of surfacing an
// error. Mirrors pwa/offlineApi.js's isServerUnreachable: a TypeError means fetch
// itself failed (DNS/connection/CORS — truly offline even if navigator.onLine lies),
// a 5xx/530 means the tunnel or backend is down. A real 4xx (e.g. 401) is a genuine
// error and must NOT silently fall back.
function isServerUnreachable(e) {
  if (e instanceof TypeError) return true;
  if (e instanceof ApiError) return e.status === 530 || e.status >= 500;
  return false;
}

/**
 * Debounced semantic search hook.
 *
 * - debounceMs before each request (default 250ms; pass lower for snappier UIs like inline popups)
 * - AbortController cancels any in-flight request when query changes
 * - Returns [] immediately if query is null / shorter than minLength
 * - Offline (or server unreachable): falls back to a simple NAME (filename) substring
 *   match over the cached vault-wide note list (see utils/offlineSearch.js).
 */
export function useSearch(query, minLength = 2, debounceMs = 250) {
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const abortRef = useRef(null);
  const timerRef = useRef(null);

  useEffect(() => {
    clearTimeout(timerRef.current);
    if (abortRef.current) { abortRef.current.abort(); abortRef.current = null; }

    const q = query?.trim() ?? '';
    if (q.length < minLength) {
      setResults([]);
      setLoading(false);
      return;
    }

    setLoading(true);

    timerRef.current = setTimeout(async () => {
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      try {
        const data = await searchNotes(q, { signal: ctrl.signal });
        setResults(data);
      } catch (e) {
        if (e.name === 'AbortError') { /* superseded by a newer query, ignore */ }
        else if (isServerUnreachable(e)) {
          try { setResults(await offlineKeywordSearch(q)); }
          catch { setResults([]); }
        } else {
          setResults([]);
        }
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    }, debounceMs);
  }, [query, minLength, debounceMs]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      clearTimeout(timerRef.current);
      if (abortRef.current) abortRef.current.abort();
    };
  }, []);

  return { results, loading };
}
