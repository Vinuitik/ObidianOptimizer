import { useState, useEffect, useCallback } from 'react';
import useStore from '../store/useStore';
import {
  fetchChronoStatus, runChronoNow,
  fetchChildren, fetchHostChildren, fetchVaultHostPath, saveVaultHostPath, pingHealth,
} from '../api/notes';
import {
  fetchSyncStatus, fetchOAuthUrl, disconnectDrive,
  triggerSyncUpload, triggerSyncDownload,
} from '../api/sync';
import FolderPicker from '../components/organisms/FolderPicker';
import styles from './SettingsPage.module.css';

const baseName = p => (p || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || p;
const dirName  = p => p.replace(/[/\\]+$/, '').replace(/[/\\][^/\\]*$/, '');

// ── Section config ────────────────────────────────────────────────────────────
// To add a new setting: add an entry to the relevant section's `fields` array,
// or add a new section object. The rest of the UI is data-driven.

const SECTIONS = [
  {
    id: 'paths',
    title: 'Resources',
    description: 'Where attachments live inside your vault. Browse to pick a folder instead of typing the path.',
    fields: [
      {
        key: 'resourcePath',
        label: 'Resources folder',
        type: 'path',
        browse: 'vault',
        placeholder: '/vault/resources/images',
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
      {
        key: 'flashcardsEnabled',
        label: 'Flashcard tests',
        type: 'select',
        options: [
          { value: true,  label: 'On — reviews run as auto-graded mini-tests built from AI flashcards' },
          { value: false, label: 'Off — slideshow: read the note and self-rate with the four buttons' },
        ],
        hint: 'Either way the grade feeds the same FSRS + bandit scheduler.',
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
    id: 'ml',
    title: 'ML / Search',
    description: 'Embedding model used for semantic search. Changing this requires re-indexing all notes (restart the backend after saving).',
    fields: [
      {
        key: 'embedModel',
        label: 'Embedding model',
        type: 'text',
        placeholder: 'mxbai-embed-large',
        hint: 'HuggingFace model ID used by the embedder service. Display only — actual model is set via EMBED_MODEL env var in docker-compose and requires a container restart to change.',
      },
    ],
  },
  {
    id: 'chrono',
    title: 'Daily Jobs',
    description: 'Hyperparameters for the automatic daily maintenance jobs (FileMover, BankruptcyCheck, SpreadCheck).',
    fields: [
      {
        key: 'maxDailyReviews',
        label: 'Max reviews per day',
        type: 'number',
        min: 1,
        hint: 'SpreadCheck redistributes notes so no single day exceeds this count.',
      },
      {
        key: 'chronicNeglectDays',
        label: 'Chronic neglect (days)',
        type: 'number',
        min: 1,
        hint: 'A note overdue by more than this many days is lapsed individually each night (FSRS forget), even if the bankruptcy limit is not reached.',
      },
      {
        key: 'bankruptcyLimit',
        label: 'Bankruptcy limit',
        type: 'number',
        min: 1,
        hint: 'If the total overdue count reaches this, every overdue note is lapsed (FSRS forget) and rescheduled in one sweep.',
      },
    ],
  },
];

// ── Component ─────────────────────────────────────────────────────────────────

export default function SettingsPage() {
  const settings       = useStore(s => s.settings);
  const applySettings  = useStore(s => s.applySettings);
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const reviewMode     = useStore(s => s.reviewMode);
  const setReviewMode  = useStore(s => s.setReviewMode);

  // Local draft state: one object per section id
  const [drafts, setDrafts]     = useState({});
  const [status, setStatus]     = useState({}); // { [sectionId]: 'idle' | 'saving' | 'saved' | 'error' }
  const [errors, setErrors]     = useState({}); // { [sectionId]: string }

  // Chrono status
  const [chronoStatus,  setChronoStatus]  = useState(null);
  const [chronoRunning, setChronoRunning] = useState(false);
  const [chronoResult,  setChronoResult]  = useState(null);
  const [chronoError,   setChronoError]   = useState(null);

  // Folder picker + vault-switch (host filesystem)
  const [picker, setPicker]         = useState(null); // props passed straight to <FolderPicker>
  const [vaultRoot, setVaultRoot]   = useState(null); // container root, caps the in-vault picker
  const [hostPath, setHostPath]     = useState(null); // HOST_VAULT_PATH from .env
  const [hostError, setHostError]   = useState(false);
  const [recreating, setRecreating] = useState(false);

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

  // Learn the vault root (caps the in-vault picker) and the real host path.
  useEffect(() => {
    fetchChildren(null).then(r => setVaultRoot(r.parentPath)).catch(() => {});
    fetchVaultHostPath()
      .then(r => setHostPath(r.path))
      .catch(e => { if (e?.status !== 401 && e?.status !== 403) setHostError(true); });
  }, []);

  // ── Folder pickers ──────────────────────────────────────────────────────────

  // Browse inside the mounted vault (for the resources folder). Never climbs
  // above the vault root.
  const loadVaultDir = useCallback(async (path) => {
    const r = await fetchChildren(path);
    const current = r.parentPath;
    const parent  = (!vaultRoot || current === vaultRoot) ? null : dirName(current);
    return { current, parent, dirs: r.folderPaths.map(p => ({ path: p, name: baseName(p) })) };
  }, [vaultRoot]);

  // Browse the real host filesystem (for the vault root) via the host-wrapper.
  const loadHostDir = useCallback(async (path) => {
    const r = await fetchHostChildren(path);
    return { current: r.path, parent: r.parent ?? null, dirs: r.dirs };
  }, []);

  function openResourcePicker(sectionId, key, value) {
    setPicker({
      title: 'Select resources folder',
      loadPath: loadVaultDir,
      initialPath: value || null,
      onSelect: (p) => { setField(sectionId, key, p); setPicker(null); },
      onClose: () => setPicker(null),
    });
  }

  function openVaultPicker() {
    setPicker({
      title: 'Select your vault folder',
      loadPath: loadHostDir,
      initialPath: hostPath || null,
      confirmLabel: 'Switch vault & restart',
      note: 'Switching the vault recreates the backend containers so the new folder can be mounted. The app will be unavailable for a few seconds and then reload.',
      onSelect: switchVault,
      onClose: () => setPicker(null),
    });
  }

  async function switchVault(path) {
    setPicker(null);
    setRecreating(true);
    try {
      await saveVaultHostPath(path);
      setHostPath(path);
    } catch {
      // The recreate may tear the backend down before it answers — that's fine,
      // the overlay polls health and reloads once it returns.
    }
  }

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
        {/* Vault root — lives in .env / the Docker mount, switched via the host
            picker which recreates the backend. Not a normal saveable field. */}
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Vault</h2>
            <p className={styles.sectionDesc}>
              The folder on your computer the app reads and writes. Switching it remounts and
              restarts the backend automatically.
            </p>
          </div>
          <div className={styles.fields}>
            <div className={styles.field}>
              <div className={styles.fieldMeta}>
                <label className={styles.label}>Vault folder</label>
                <span className={styles.hint}>
                  {hostError
                    ? 'Folder picker unavailable — the host helper is not running.'
                    : 'The location on your machine mounted into the app.'}
                </span>
              </div>
              <div className={styles.pathRow}>
                <input
                  className={`${styles.input} ${styles.inputPath}`}
                  value={hostPath ?? ''}
                  readOnly
                  placeholder={hostError ? 'unavailable' : 'Loading…'}
                />
                <button
                  type="button"
                  className={styles.browseBtn}
                  disabled={hostError || hostPath == null}
                  onClick={openVaultPicker}
                >
                  Browse…
                </button>
              </div>
            </div>
          </div>
        </div>

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
                      onBrowse={() => openResourcePicker(section.id, field.key, drafts[section.id]?.[field.key] ?? '')}
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

        {/* Preferences — local browser settings (not persisted to backend) */}
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Preferences</h2>
            <p className={styles.sectionDesc}>
              UI preferences stored in your browser. Cleared when browser data is reset.
            </p>
          </div>
          <div className={styles.fields}>
            <div className={styles.field}>
              <div className={styles.fieldMeta}>
                <label className={styles.label} htmlFor="pref-reviewMode">Review style</label>
                <span className={styles.hint}>
                  Flashcard mode hides the right panel on the main page and moves review to a dedicated flashcard view at <code>/review</code>.
                </span>
              </div>
              <select
                id="pref-reviewMode"
                className={styles.input}
                value={reviewMode}
                onChange={e => setReviewMode(e.target.value)}
              >
                <option value="inline">Inline — review queue in main right panel</option>
                <option value="flashcard">Flashcard — dedicated review page with flashcards</option>
              </select>
            </div>
          </div>
        </div>

        {/* Google Drive sync — sign in, passphrase, enable, manual triggers */}
        <DriveSyncPanel loaded={loaded} isAuthenticated={isAuthenticated} />

        {/* Chrono status — read-only panel with manual trigger */}
        <div className={styles.section}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Chrono Status</h2>
            <p className={styles.sectionDesc}>
              Daily jobs run automatically at 2am and on startup. FileMover → BankruptcyCheck → SpreadCheck.
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
              Moved {chronoResult.filesMoved} file(s) · Overdue {chronoResult.bankruptcy.overdueCount} ·
              Neglect-lapsed {chronoResult.bankruptcy.chronicNeglected} note(s)
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

      {picker && <FolderPicker {...picker} />}
      {recreating && <RecreatingOverlay />}
    </div>
  );
}

// ── Google Drive sync panel ─────────────────────────────────────────────────────
// Sign in with Google (OAuth, drive.file scope — synced files land in YOUR Drive),
// set the encryption passphrase, toggle auto-sync, and trigger upload/download.
// Secrets are write-only: the backend only reports whether they are saved.

function DriveSyncPanel({ loaded, isAuthenticated }) {
  const settings      = useStore(s => s.settings);
  const applySettings = useStore(s => s.applySettings);

  const [sync, setSync]         = useState(null);   // /sync/status payload
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [passphrase, setPassphrase]     = useState('');
  const [busy, setBusy]         = useState(null);   // 'save' | 'connect' | 'disconnect' | 'upload' | 'download'
  const [error, setError]       = useState(null);
  const [notice, setNotice]     = useState(null);

  const refreshStatus = useCallback(() => {
    fetchSyncStatus().then(setSync).catch(() => setSync(null));
  }, []);

  useEffect(() => { if (isAuthenticated) refreshStatus(); }, [isAuthenticated, refreshStatus]);

  // Prefill the client id from saved settings once they load.
  useEffect(() => { setClientId(settings.syncClientId ?? ''); }, [settings.syncClientId]);

  // Returning from Google: /settings?drive=connected|error&reason=…
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const drive = params.get('drive');
    if (!drive) return;
    if (drive === 'connected') setNotice('Google Drive connected.');
    else setError(`Connect failed: ${params.get('reason') || 'unknown error'}`);
    window.history.replaceState(null, '', window.location.pathname);
  }, []);

  async function run(kind, fn) {
    setBusy(kind);
    setError(null);
    setNotice(null);
    try {
      await fn();
      refreshStatus();
    } catch (e) {
      setError(e.message ?? 'Request failed.');
    } finally {
      setBusy(null);
    }
  }

  const credsDirty =
    clientId !== (settings.syncClientId ?? '') || clientSecret !== '' || passphrase !== '';

  function saveCreds() {
    return run('save', async () => {
      const patch = { syncClientId: clientId.trim() };
      if (clientSecret.trim()) patch.syncClientSecret = clientSecret.trim();
      if (passphrase.trim())   patch.syncPassphrase   = passphrase.trim();
      await applySettings(patch);
      setClientSecret('');
      setPassphrase('');
      setNotice('Saved.');
    });
  }

  function connect() {
    return run('connect', async () => {
      window.location.href = await fetchOAuthUrl();
    });
  }

  const connected = Boolean(sync?.connected);
  const queueLine = sync
    ? `${sync.pending ?? 0} pending · ${sync.done ?? 0} synced · ${sync.failed ?? 0} failed`
    : '—';

  return (
    <div className={styles.section}>
      <div className={styles.sectionHeader}>
        <h2 className={styles.sectionTitle}>Google Drive Sync</h2>
        <p className={styles.sectionDesc}>
          Encrypted per-file backup of the vault to your Google Drive. Create an OAuth client
          (type “Web application”) in Google Cloud Console, add{' '}
          <code>{window.location.origin}/api/sync/oauth/callback</code> as an authorised redirect
          URI, paste its credentials here, then connect. Files are AES-256 encrypted with your
          passphrase before upload — Google never sees plaintext.
        </p>
      </div>

      <div className={styles.fields}>
        <div className={styles.field}>
          <div className={styles.fieldMeta}>
            <label className={styles.label}>Status</label>
          </div>
          <span className={styles.hint}>
            {!sync ? 'Unavailable — sign in first.'
              : connected
                ? `Connected as ${sync.accountEmail || 'Google account'} · ${queueLine}`
                : `Not connected · ${queueLine}`}
            {sync && !sync.encryptionConfigured ? ' · passphrase not set' : ''}
          </span>
        </div>

        <div className={styles.field}>
          <div className={styles.fieldMeta}>
            <label className={styles.label} htmlFor="sync-client-id">OAuth client ID</label>
          </div>
          <input
            id="sync-client-id" type="text"
            className={`${styles.input} ${styles.inputPath}`}
            value={clientId} disabled={!loaded}
            placeholder="…apps.googleusercontent.com"
            onChange={e => setClientId(e.target.value)}
          />
        </div>

        <div className={styles.field}>
          <div className={styles.fieldMeta}>
            <label className={styles.label} htmlFor="sync-client-secret">OAuth client secret</label>
          </div>
          <input
            id="sync-client-secret" type="password"
            className={styles.input}
            value={clientSecret} disabled={!loaded}
            placeholder={settings.syncClientSecretSet ? '•••••• (saved — type to replace)' : ''}
            onChange={e => setClientSecret(e.target.value)}
          />
        </div>

        <div className={styles.field}>
          <div className={styles.fieldMeta}>
            <label className={styles.label} htmlFor="sync-passphrase">Encryption passphrase</label>
            <span className={styles.hint}>
              Same passphrase on every device. Changing it makes files already on Drive
              unreadable until re-uploaded.
            </span>
          </div>
          <input
            id="sync-passphrase" type="password"
            className={styles.input}
            value={passphrase} disabled={!loaded}
            placeholder={settings.syncPassphraseSet ? '•••••• (saved — type to replace)' : ''}
            onChange={e => setPassphrase(e.target.value)}
          />
        </div>

        <div className={styles.field}>
          <div className={styles.fieldMeta}>
            <label className={styles.label} htmlFor="sync-enabled">Automatic sync</label>
            <span className={styles.hint}>Uploads pending changes on a schedule (default every 6h).</span>
          </div>
          <select
            id="sync-enabled"
            className={styles.input}
            value={String(settings.syncEnabled ?? false)}
            disabled={!loaded || busy != null}
            onChange={e => run('save', () => applySettings({ syncEnabled: e.target.value === 'true' }))}
          >
            <option value="false">Off</option>
            <option value="true">On — scheduled background uploads</option>
          </select>
        </div>
      </div>

      {error  && <span className={styles.errorMsg}>{error}</span>}
      {notice && !error && <span className={styles.savedMsg}>{notice}</span>}

      <div className={styles.sectionFooter}>
        <button
          className={styles.saveBtn}
          disabled={!loaded || busy != null || !credsDirty}
          onClick={saveCreds}
        >
          {busy === 'save' ? 'Saving…' : 'Save'}
        </button>
        {!connected ? (
          <button
            className={styles.saveBtn}
            disabled={!loaded || busy != null || !(settings.syncClientId && settings.syncClientSecretSet)}
            onClick={connect}
            title={!(settings.syncClientId && settings.syncClientSecretSet)
              ? 'Save the OAuth client id and secret first' : undefined}
          >
            {busy === 'connect' ? 'Redirecting…' : 'Connect Google Drive'}
          </button>
        ) : (
          <>
            <button
              className={styles.saveBtn}
              disabled={busy != null}
              onClick={() => run('upload', triggerSyncUpload)}
            >
              {busy === 'upload' ? 'Uploading…' : 'Sync now'}
            </button>
            <button
              className={styles.saveBtn}
              disabled={busy != null}
              onClick={() => run('download', triggerSyncDownload)}
            >
              {busy === 'download' ? 'Pulling…' : 'Pull from Drive'}
            </button>
            <button
              className={styles.saveBtn}
              disabled={busy != null}
              onClick={() => run('disconnect', disconnectDrive)}
            >
              {busy === 'disconnect' ? 'Disconnecting…' : 'Disconnect'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

// ── Vault recreation overlay ────────────────────────────────────────────────────
// Blocks the UI while `docker compose up -d` recreates the backend, polling until
// it returns, then reloads. Falls back to reloading after a grace period in case
// the down-window was missed (or no recreate was needed).

function RecreatingOverlay() {
  useEffect(() => {
    let cancelled = false;
    let sawDown = false;
    let ticks = 0;
    const id = setInterval(async () => {
      ticks += 1;
      const ok = await pingHealth();
      if (cancelled) return;
      if (!ok) { sawDown = true; return; }
      if (sawDown || ticks >= 15) { clearInterval(id); window.location.reload(); }
    }, 2000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  return (
    <div className={styles.recreateOverlay}>
      <div className={styles.recreateBox}>
        <div className={styles.spinner} />
        <p className={styles.recreateText}>Switching vault &amp; restarting…</p>
        <p className={styles.recreateSub}>
          Reconnecting once the backend is back. This page reloads automatically.
        </p>
      </div>
    </div>
  );
}

// ── Field input ───────────────────────────────────────────────────────────────

function FieldInput({ id, field, value, disabled, onChange, onEnter, onBrowse }) {
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

  const input = (
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

  if (!field.browse) return input;

  return (
    <div className={styles.pathRow}>
      {input}
      <button
        type="button"
        className={styles.browseBtn}
        disabled={disabled}
        onClick={onBrowse}
      >
        Browse…
      </button>
    </div>
  );
}
