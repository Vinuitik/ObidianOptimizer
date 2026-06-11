import { useState, useEffect, useRef } from 'react';
import { searchNotes } from '../api/notes';

/**
 * Debounced semantic search hook.
 *
 * - 500ms debounce before each request
 * - AbortController cancels any in-flight request when query changes
 * - Returns [] immediately if query is null / shorter than minLength
 */
export function useSearch(query, minLength = 2) {
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
        if (e.name !== 'AbortError') setResults([]);
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    }, 500);
  }, [query, minLength]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      clearTimeout(timerRef.current);
      if (abortRef.current) abortRef.current.abort();
    };
  }, []);

  return { results, loading };
}
