import { useState, useEffect } from 'react';
import useStore from '../store/useStore';
import { fetchChronoStatus, runChronoNow } from '../api/notes';
import styles from './SettingsPage.module.css';

// ── Section config ────────────────────────────────────────────────────────────
// To add a new setting: add an entry to the relevant section's `fields` array,
// or add a new section object. The rest of the UI is data-driven.

const SECTIONS = [
  {
    id: 'vault',
    title: 'Vault',
    description: 'Paths to your Obsidian vault and attachments. Changing the vault path will re-index all note links.',
    fields: [
      {
        key: 'vaultPath',
        label: 'Vault folder',
        type: 'path',
        placeholder: 'C:/Users/you/MyVault',
        hint: 'Root directory of your Obsidian vault.',
      },
      {
        key: 'resourcePath',
        label: 'Resources folder',
        type: 'path',
        placeholder: 'C:/Users/you/MyVault/resources/images',
        hint: 'Where images and other attachments are stored.',
      },
    ],
  },
  {
    id: 'review',
    title: 'Review',
    description: 'Controls how the spaced-repetition review queue behaves.',
    fields: [
      {
        key: 'reviewPageSize',
        label: 'Page size',
        type: 'number',
        min: 1,
        max: 500,
        hint: 'How many notes are loaded per review session.',
      },
    ],
  },
  {
    id: 'advanced',
    title: 'Advanced',
    description: 'Performance and startup behaviour.',
    fields: [
      {
        key: 'startupSyncMode',
        label: 'Startup sync',
        type: 'select',
        options: [
          { value: 'blocking', label: 'Blocking (default) — app waits for index sync before accepting requests' },
          { value: 'async',    label: 'Async — app starts immediately, sync runs in background' },
        ],
        hint: 'How the note index is synchronised with disk on startup.',
      },
    ],
  },
  {
    id: 'chrono',
    title: 'Daily Jobs',
    description: 'Hyperparameters for the automatic daily maintenance jobs (FileMover, FileChecker, BankruptcyCheck, SpreadCheck).',
    fields: [
      {
        key: 'maxDailyReviews',
        label: 'Max reviews per day',
        type: 'number',
        min: 1,
        hint: 'SpreadCheck redistributes notes so no single day exceeds this count.',
      },
      {
        key: 'bankruptcyLimit',
        label: 'Bankruptcy limit',
        type: 'number',
        min: 1,
        hint: 'If overdue notes reach this count, BankruptcyCheck halves intervals and redistributes them.',
      },
    ],
  },
];

// ── Component ─────────────────────────────────────────────────────────────────

export default function SettingsPage() {
  const settings      = useStore(s => s.settings);
  const applySettings = useStore(s => s.applySettings);
  const isAuthenticated = useStore(s => s.isAuthenticated);

  // Local draft state: one object per section id
  const [drafts, setDrafts]     = useState({});
  const [status, setStatus]     = useState({}); // { [sectionId]: 'idle' | 'saving' | 'saved' | 'error' }
  const [errors, setErrors]     = useState({}); // { [sectionId]: string }

  // Chrono status
  const [chronoStatus,  setChronoStatus]  = useState(null);
  const [chronoRunning, setChronoRunning] = useState(false);
  const [chronoResult,  setChronoResult]  = useState(null);
  const [chronoError,   setChronoError]   = useState(null);

  // Initialise drafts from store once settings load
  useEffect(() => {
    if (!settings.vaultPath) return;
    const initial = {};
    for (const section of SECTIONS) {
      const draft = {};
      for (const field of section.fields) {
        draft[field.key] = String(settings[field.key] ?? '');
      }
      initial[section.id] = draft;
    }
    setDrafts(initial);
  }, [settings.vaultPath]);

  // Fetch chrono status on mount
  useEffect(() => {
    fetchChronoStatus()
      .then(setChronoStatus)
      .catch(() => {});
  }, []);

  function setField(sectionId, key, value) {
    setDrafts(prev => ({
      ...prev,
      [sectionId]: { ...prev[sectionId], [key]: value },
    }));
    // Clear any previous status when user edits
    setStatus(prev => ({ ...prev, [sectionId]: 'idle' }));
    setErrors(prev => ({ ...prev, [sectionId]: undefined }));
  }

  async function saveSection(section) {
    const draft = drafts[section.id] ?? {};
    const patch = {};

    for (const field of section.fields) {
      const raw = draft[field.key];
      if (field.type === 'number') {
        const n = parseInt(raw, 10);
        const belowMin = isNaN(n) || n < (field.min ?? 1);
        const aboveMax = field.max != null && n > field.max;
        if (belowMin || aboveMax) {
          const msg = field.max != null
            ? `${field.label} must be between ${field.min ?? 1} and ${field.max}.`
            : `${field.label} must be a positive integer.`;
          setErrors(prev => ({ ...prev, [section.id]: msg }));
          return;
        }
        patch[field.key] = n;
      } else {
        if (!raw || !raw.trim()) {
          setErrors(prev => ({ ...prev, [section.id]: `${field.label} cannot be empty.` }));
          return;
        }
        patch[field.key] = raw.trim();
      }
    }

    setStatus(prev => ({ ...prev, [section.id]: 'saving' }));
    setErrors(prev => ({ ...prev, [section.id]: undefined }));

    try {
      await applySettings(patch);
      setStatus(prev => ({ ...prev, [section.id]: 'saved' }));
      setTimeout(() => setStatus(prev => ({ ...prev, [section.id]: 'idle' })), 2500);
    } catch (e) {
      setErrors(prev => ({ ...prev, [section.id]: e.message ?? 'Failed to save.' }));
      setStatus(prev => ({ ...prev, [section.id]: 'error' }));
    }
  }

  function isDirty(section) {
    const draft = drafts[section.id];
    if (!draft) return false;
    return section.fields.some(f => String(settings[f.key] ?? '') !== draft[f.key]);
  }

  async function handleChronoRun() {
    setChronoRunning(true);
    setChronoResult(null);
    setChronoError(null);
    try {
      const result = await runChronoNow();
      setChronoResult(result);
      setChronoStatus({ lastRunDate: result.date });
    } catch (e) {
      setChronoError('Run failed — check server logs.');
    } finally {
      setChronoRunning(false);
    }
  }

  const loaded = Boolean(settings.vaultPath);

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Settings</h1>
        <p className={styles.subtitle}>Configure your vault and preferences.</p>
      </div>

      <div className={styles.sections}>
        {SECTIONS.map(section => {

          const st   = status[section.id] ?? 'idle';
          const err  = errors[section.id];
          const dirty = isDirty(section);

          return (
            <div key={section.id} className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>{section.title}</h2>
                <p className={styles.sectionDesc}>{section.description}</p>
              </div>

              <div className={styles.fields}>
                {section.fields.map(field => (
                  <div key={field.key} className={styles.field}>
                    <div className={styles.fieldMeta}>
                      <label className={styles.label} htmlFor={`${section.id}-${field.key}`}>
                        {field.label}
                      </label>
                      {field.hint && <span className={styles.hint}>{field.hint}</span>}
                    </div>
                    <FieldInput
                      id={`${section.id}-${field.key}`}
                      field={field}
                      value={drafts[section.id]?.[field.key] ?? ''}
                      disabled={!loaded}
                      onChange={val => setField(section.id, field.key, val)}
                      onEnter={() => saveSection(section)}
                    />
                  </div>
                ))}
              </div>

              <div className={styles.sectionFooter}>
                {err && <span className={styles.errorMsg}>{err}</span>}
                {st === 'saved' && !err && (
                  <span className={styles.savedMsg}>Saved.</span>
                )}
                <button
                  className={styles.saveBtn}
                  disabled={!loaded || st === 'saving' || !dirty}
                  onClick={() => saveSection(section)}
                >
                  {st === 'saving' ? 'Saving…' : 'Save'}
                </button>
              </div>
            </div>
          );
        })}

        {/* Chrono status — read-only panel with manual trigger */}
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Chrono Status</h2>
            <p className={styles.sectionDesc}>
              Daily jobs run automatically at 2am and on startup. FileMover → FileChecker → BankruptcyCheck → SpreadCheck.
            </p>
          </div>

          <div className={styles.fields}>
            <div className={styles.field}>
              <div className={styles.fieldMeta}>
                <label className={styles.label}>Last run</label>
              </div>
              <span className={styles.hint}>
                {chronoStatus?.lastRunDate || 'Never'}
              </span>
            </div>
          </div>

          {chronoResult && (
            <p className={styles.hint} style={{ marginTop: '8px' }}>
              Moved {chronoResult.filesMoved} file(s) · Fixed {chronoResult.filesFixed} note(s) ·
              Overdue {chronoResult.bankruptcy.overdueCount}
              {chronoResult.bankruptcy.declared ? ' (bankruptcy declared)' : ''} ·
              Shifted {chronoResult.spread.moved} note(s)
            </p>
          )}
          {chronoError && <span className={styles.errorMsg}>{chronoError}</span>}

          <div className={styles.sectionFooter}>
            <button
              className={styles.saveBtn}
              disabled={chronoRunning || !isAuthenticated}
              onClick={handleChronoRun}
            >
              {chronoRunning ? 'Running…' : 'Run now'}
            </button>
          </div>
        </div>

      </div>
    </div>
  );
}

// ── Field input ───────────────────────────────────────────────────────────────

function FieldInput({ id, field, value, disabled, onChange, onEnter }) {
  function handleKey(e) {
    if (e.key === 'Enter') onEnter();
  }

  if (field.type === 'select') {
    return (
      <select
        id={id}
        className={styles.input}
        value={value}
        disabled={disabled}
        onChange={e => onChange(e.target.value)}
      >
        {field.options.map(opt => (
          <option key={opt.value} value={opt.value}>{opt.label}</option>
        ))}
      </select>
    );
  }

  if (field.type === 'number') {
    return (
      <input
        id={id}
        type="number"
        className={styles.input}
        value={value}
        min={field.min}
        max={field.max}
        disabled={disabled}
        onChange={e => onChange(e.target.value)}
        onKeyDown={handleKey}
      />
    );
  }

  return (
    <input
      id={id}
      type="text"
      className={`${styles.input} ${styles.inputPath}`}
      value={value}
      placeholder={field.placeholder ?? ''}
      disabled={disabled}
      onChange={e => onChange(e.target.value)}
      onKeyDown={handleKey}
    />
  );
}
