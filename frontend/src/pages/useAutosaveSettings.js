import { useCallback, useEffect, useRef, useState } from 'react';

// Autosave for simple settings fields.
//   draft[key]   – what the control shows (always a string)
//   status[key]  – 'saving' | 'saved' | 'error' | undefined
//   errors[key]  – inline message for the field
// Selects/pickers call commit() right after setDraft(); text/number fields commit on blur / Enter.
// A commit is a no-op when the value equals what's already saved, so blur-without-edit never hits the API.

const SAVED_MS = 2000;

export function parseField(field, raw) {
  const text = String(raw ?? '');
  if (field.type === 'number') {
    const n = parseInt(text, 10);
    const min = field.min ?? 1;
    if (Number.isNaN(n) || n < min || (field.max != null && n > field.max)) {
      return { error: field.max != null
        ? `${field.label} must be between ${min} and ${field.max}.`
        : `${field.label} must be a whole number, ${min} or more.` };
    }
    return { value: n };
  }
  if (field.type === 'select') {
    const opt = field.options.find(o => String(o.value) === text);
    return opt ? { value: opt.value } : { error: `Pick one of the options for ${field.label}.` };
  }
  if (!text.trim()) return { error: `${field.label} can’t be empty.` };
  return { value: text.trim() };
}

export default function useAutosaveSettings(settings, applySettings, fields) {
  const [draft, setDraftState] = useState({});
  const [status, setStatus] = useState({});
  const [errors, setErrors] = useState({});
  const timers = useRef({});
  const draftRef = useRef(draft);
  draftRef.current = draft;

  // Seed drafts from the store; never clobber a field the user is mid-edit on (dirty).
  const dirty = useRef(new Set());
  useEffect(() => {
    setDraftState(prev => {
      const next = { ...prev };
      for (const f of fields) {
        if (!dirty.current.has(f.key)) next[f.key] = String(settings[f.key] ?? '');
      }
      return next;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings]);

  useEffect(() => () => Object.values(timers.current).forEach(clearTimeout), []);

  const setDraft = useCallback((key, value) => {
    dirty.current.add(key);
    setDraftState(p => ({ ...p, [key]: value }));
    setErrors(p => ({ ...p, [key]: undefined }));
    setStatus(p => ({ ...p, [key]: undefined }));
  }, []);

  const commit = useCallback(async (field, override) => {
    const raw = override !== undefined ? override : draftRef.current[field.key];
    if (raw === undefined) return;
    if (String(settings[field.key] ?? '') === String(raw).trim()) { dirty.current.delete(field.key); return; }
    const parsed = parseField(field, raw);
    if (parsed.error) {
      setErrors(p => ({ ...p, [field.key]: parsed.error }));
      return;
    }
    setStatus(p => ({ ...p, [field.key]: 'saving' }));
    try {
      await applySettings({ [field.key]: parsed.value });
      dirty.current.delete(field.key);
      setStatus(p => ({ ...p, [field.key]: 'saved' }));
      clearTimeout(timers.current[field.key]);
      timers.current[field.key] = setTimeout(
        () => setStatus(p => ({ ...p, [field.key]: undefined })), SAVED_MS);
    } catch (e) {
      setStatus(p => ({ ...p, [field.key]: 'error' }));
      setErrors(p => ({ ...p, [field.key]: e?.message && !/^\d+$/.test(e.message) ? e.message : 'Couldn’t save — try again.' }));
    }
  }, [settings, applySettings]);

  return { draft, status, errors, setDraft, commit };
}
