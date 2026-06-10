import { useState, useEffect } from 'react';
import useStore from '../store/useStore';
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
];

// ── Component ─────────────────────────────────────────────────────────────────

export default function SettingsPage() {
  const settings     = useStore(s => s.settings);
  const applySettings = useStore(s => s.applySettings);

  // Local draft state: one object per section id
  const [drafts, setDrafts]     = useState({});
  const [status, setStatus]     = useState({}); // { [sectionId]: 'idle' | 'saving' | 'saved' | 'error' }
  const [errors, setErrors]     = useState({}); // { [sectionId]: string }

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
        if (isNaN(n) || n < (field.min ?? 1) || n > (field.max ?? 9999)) {
          setErrors(prev => ({ ...prev, [section.id]: `${field.label} must be between ${field.min} and ${field.max}.` }));
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
      </div>
    </div>
  );
}

// ── Field input ───────────────────────────────────────────────────────────────

function FieldInput({ id, field, value, disabled, onChange, onEnter }) {
  function handleKey(e) {
    if (e.key === 'Enter') onEnter();
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
