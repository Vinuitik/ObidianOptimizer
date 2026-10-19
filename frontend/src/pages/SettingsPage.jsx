import { useState, useEffect, useCallback } from 'react';
import useStore from '../store/useStore';
import {
  fetchChronoStatus, runChronoNow,
  fetchChildren, fetchVaultHostPath,
} from '../api/notes';
import {
  fetchSyncStatus, fetchOAuthUrl, disconnectDrive,
  triggerFullBackup, triggerDbRestore,
} from '../api/sync';
import FolderPicker from '../components/organisms/FolderPicker';
import Button from '../components/atoms/Button';
import Badge from '../components/atoms/Badge';
import Input from '../components/atoms/Input';
import Select from '../components/atoms/Select';
import Card from '../components/molecules/Card';
import Field from '../components/molecules/Field';
import PageHeader from '../components/molecules/PageHeader';
import useAutosaveSettings from './useAutosaveSettings';
import styles from './SettingsPage.module.css';

const baseName = p => (p || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || p;
const dirName  = p => p.replace(/[/\\]+$/, '').replace(/[/\\][^/\\]*$/, '');

// ── Field config ──────────────────────────────────────────────────────────────
// To add a setting: add a field to the right group. Simple fields autosave
// (selects on change, text/number on blur/Enter) via useAutosaveSettings.
// Groups render in order: Vault · Learning · Feed · Sync · Advanced.

const F = {
  resourcePath: {
    key: 'resourcePath', label: 'Resources folder', type: 'path', browse: 'vault',
    placeholder: '/vault/resources/images',
    hint: 'Where images and other attachments are stored inside your vault.',
  },
  reviewPageSize: {
    key: 'reviewPageSize', label: 'Notes loaded per review', type: 'number', min: 1, max: 500,
    hint: 'How many due notes are fetched at a time.',
  },
  maxDailyReviews: {
    key: 'maxDailyReviews', label: 'Max reviews per day', type: 'number', min: 1,
    hint: 'The nightly job spreads notes out so no day goes over this.',
  },
  flashcardsEnabled: {
    key: 'flashcardsEnabled', label: 'Review style', type: 'select',
    options: [
      { value: true,  label: 'Mixed — some flashcard tests, the rest read & rate' },
      { value: false, label: 'Read only — read and rate every note' },
    ],
    hint: 'Mixed tests your oldest due card-bearing notes as flashcards each day; everything else is read and self-rated. Existing cards are kept either way.',
  },
  maxDailyFlashcards: {
    key: 'maxDailyFlashcards', label: 'Max flashcard tests per day', type: 'number', min: 0,
    hint: '0 turns flashcard tests off. Can’t be higher than max reviews per day.',
  },
  tracksEnabled: {
    key: 'tracksEnabled', label: 'Tracks tab', type: 'select',
    options: [
      { value: true,  label: 'Shown in the nav' },
      { value: false, label: 'Hidden (tracks are kept)' },
    ],
    hint: 'Only hides the tab — your tracks and schedules are never deleted.',
  },
  chronicNeglectDays: {
    key: 'chronicNeglectDays', label: 'Chronic neglect (days)', type: 'number', min: 1,
    hint: 'A note overdue this long is reset overnight, even if the bankruptcy limit isn’t hit.',
  },
  bankruptcyLimit: {
    key: 'bankruptcyLimit', label: 'Bankruptcy limit', type: 'number', min: 1,
    hint: 'If this many notes are overdue, all of them are reset and rescheduled in one sweep.',
  },
  feedAutoAccept: {
    key: 'feedAutoAccept', label: 'New feed items', type: 'select',
    options: [
      { value: false, label: 'Swipe each one (default)' },
      { value: true,  label: 'Send straight to the Inbox' },
    ],
    hint: 'Each source can override this. A source’s first fetch (its backlog) is never auto-accepted.',
  },
  startupSyncMode: {
    key: 'startupSyncMode', label: 'On startup', type: 'select',
    options: [
      { value: 'blocking', label: 'Wait for the note index to sync (default)' },
      { value: 'async',    label: 'Open immediately, sync in the background' },
    ],
    hint: 'How the note index catches up with your files when the app starts.',
  },
};

const ALL_FIELDS = Object.values(F);

// ── Small shared bits ─────────────────────────────────────────────────────────

function SavedMark({ status }) {
  return (
    <span className={styles.saveMark} role="status" aria-live="polite">
      {status === 'saving' && <span className={styles.saving}>Saving…</span>}
      {status === 'saved' && <span className={styles.saved}>Saved ✓</span>}
    </span>
  );
}

function CopyButton({ text, label = 'Copy' }) {
  const showToast = useStore(s => s.showToast);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      showToast?.('Copied');
    } catch {
      showToast?.({ message: 'Couldn’t copy — select it and copy by hand.', tone: 'error' });
    }
  }
  return <Button size="sm" variant="ghost" onClick={copy} disabled={!text}>{label}</Button>;
}

function Group({ title, description, children }) {
  return (
    <Card as="section" className={styles.group} aria-label={title}>
      <Card.Header title={title} subtitle={description} />
      <div className={styles.fields}>{children}</div>
    </Card>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function SettingsPage() {
  const settings        = useStore(s => s.settings);
  const applySettings   = useStore(s => s.applySettings);
  const isAuthenticated = useStore(s => s.isAuthenticated);

  const { draft, status, errors, setDraft, commit } =
    useAutosaveSettings(settings, applySettings, ALL_FIELDS);

  const [chronoStatus,  setChronoStatus]  = useState(null);
  const [chronoRunning, setChronoRunning] = useState(false);
  const [chronoResult,  setChronoResult]  = useState(null);
  const [chronoError,   setChronoError]   = useState(null);

  const [picker, setPicker]         = useState(null);
  const [vaultRoot, setVaultRoot]   = useState(null);
  const [hostPath, setHostPath]     = useState(null);
  const [hostError, setHostError]   = useState(false);

  useEffect(() => {
    fetchChronoStatus().then(setChronoStatus).catch(() => {});
  }, []);

  useEffect(() => {
    fetchChildren(null).then(r => setVaultRoot(r.parentPath)).catch(() => {});
    fetchVaultHostPath()
      .then(r => setHostPath(r.path))
      .catch(e => { if (e?.status !== 401 && e?.status !== 403) setHostError(true); });
  }, []);

  // Browse inside the mounted vault (resources folder). Never climbs above the vault root.
  const loadVaultDir = useCallback(async (path) => {
    const r = await fetchChildren(path);
    const current = r.parentPath;
    const parent  = (!vaultRoot || current === vaultRoot) ? null : dirName(current);
    return { current, parent, dirs: r.folderPaths.map(p => ({ path: p, name: baseName(p) })) };
  }, [vaultRoot]);

  function openResourcePicker(field) {
    setPicker({
      title: 'Select resources folder',
      loadPath: loadVaultDir,
      initialPath: draft[field.key] || null,
      onSelect: (p) => { setDraft(field.key, p); commit(field, p); setPicker(null); },
      onClose: () => setPicker(null),
    });
  }

  async function handleChronoRun() {
    setChronoRunning(true);
    setChronoResult(null);
    setChronoError(null);
    try {
      const result = await runChronoNow();
      setChronoResult(result);
      setChronoStatus({ lastRunDate: result.date });
    } catch {
      setChronoError('The nightly jobs didn’t finish — check the server logs.');
    } finally {
      setChronoRunning(false);
    }
  }

  const loaded = Boolean(settings.vaultPath);

  // One autosaving control. Selects commit on change; text/number on blur or Enter.
  function renderField(field) {
    const id = `set-${field.key}`;
    const label = <>{field.label}<SavedMark status={status[field.key]} /></>;
    const err = errors[field.key];
    const value = draft[field.key] ?? '';
    let control;

    if (field.type === 'select') {
      control = (
        <Select
          id={id} value={value} disabled={!loaded}
          options={field.options.map(o => ({ value: String(o.value), label: o.label }))}
          onChange={e => { setDraft(field.key, e.target.value); commit(field, e.target.value); }}
        />
      );
    } else if (field.type === 'number') {
      control = (
        <Input
          id={id} type="number" inputMode="numeric" min={field.min} max={field.max}
          className={styles.numInput} value={value} disabled={!loaded}
          onChange={e => setDraft(field.key, e.target.value)}
          onBlur={() => commit(field)}
          onKeyDown={e => { if (e.key === 'Enter') e.currentTarget.blur(); }}
        />
      );
    } else {
      control = (
        <Input
          id={id} className={styles.mono} value={value} placeholder={field.placeholder ?? ''} disabled={!loaded}
          onChange={e => setDraft(field.key, e.target.value)}
          onBlur={() => commit(field)}
          onKeyDown={e => { if (e.key === 'Enter') e.currentTarget.blur(); }}
        />
      );
    }

    if (field.browse) {
      return (
        <div key={field.key} className={styles.fieldRow}>
          <Field label={label} hint={field.hint} error={err}>
            <Input
              id={id} className={styles.mono} value={value} placeholder={field.placeholder ?? ''} disabled={!loaded}
              onChange={e => setDraft(field.key, e.target.value)}
              onBlur={() => commit(field)}
              onKeyDown={e => { if (e.key === 'Enter') e.currentTarget.blur(); }}
            />
          </Field>
          <div className={styles.pathActions}>
            <Button size="sm" disabled={!loaded} onClick={() => openResourcePicker(field)}>Browse…</Button>
            <CopyButton text={value} />
          </div>
        </div>
      );
    }

    return (
      <div key={field.key} className={styles.fieldRow}>
        <Field label={label} hint={field.hint} error={err}>{control}</Field>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <PageHeader title="Settings" subtitle="Changes save automatically." />

      <div className={styles.groups}>
        <Group title="Vault" description="Where your notes live.">
          <div className={styles.fieldRow}>
            <Field
              label="Vault folder"
              hint={hostError
                ? 'Needs the helper app running on your computer, and it isn’t right now.'
                : 'The folder on your computer that the app reads and writes. To change it, edit HOST_VAULT_PATH in .env and restart.'}
            >
              <Input
                className={styles.mono} readOnly value={hostPath ?? ''}
                placeholder={hostError ? 'Unavailable' : 'Loading…'}
              />
            </Field>
            <div className={styles.pathActions}>
              <CopyButton text={hostPath ?? ''} />
            </div>
          </div>
          {renderField(F.resourcePath)}
        </Group>

        <Group title="Learning" description="Reviews, flashcards, tracks and the nightly clean-up.">
          {renderField(F.flashcardsEnabled)}
          {renderField(F.maxDailyReviews)}
          {renderField(F.maxDailyFlashcards)}
          {renderField(F.reviewPageSize)}
          {renderField(F.tracksEnabled)}
          {renderField(F.chronicNeglectDays)}
          {renderField(F.bankruptcyLimit)}
        </Group>

        <Group title="Feed" description="Subscribed sources in Learn ▸ Feed.">
          {renderField(F.feedAutoAccept)}
        </Group>

        <DriveSyncPanel loaded={loaded} isAuthenticated={isAuthenticated} />

        <Group title="Advanced" description="Startup behaviour and background jobs.">
          {renderField(F.startupSyncMode)}
          <div className={styles.fieldRow}>
            <Field
              label="Search model"
              hint="Set by the server (EMBED_MODEL in docker-compose), so it can’t be changed here. Changing it means restarting and re-indexing every note."
            >
              <Input className={styles.mono} readOnly value={settings.embedModel ?? ''} placeholder="Not reported" />
            </Field>
          </div>
          <div className={styles.fieldRow}>
            <Field
              label="Nightly jobs"
              hint="Move files, reset badly overdue notes and spread out the review load. Runs at 2am and on startup."
            >
              <div className={styles.inline}>
                <span className={styles.value}>Last run: {chronoStatus?.lastRunDate || 'never'}</span>
                <Button size="sm" loading={chronoRunning} disabled={!isAuthenticated} onClick={handleChronoRun}>Run now</Button>
              </div>
            </Field>
            {chronoResult && (
              <p className={styles.note} role="status">
                Moved {chronoResult.filesMoved} file(s) · {chronoResult.bankruptcy.overdueCount} overdue ·
                {' '}{chronoResult.bankruptcy.chronicNeglected} neglected note(s) reset
                {chronoResult.bankruptcy.declared ? ' (bankruptcy declared)' : ''} ·
                {' '}{chronoResult.spread.moved} note(s) rescheduled
              </p>
            )}
            {chronoError && <p className={styles.error} role="alert">{chronoError}</p>}
          </div>
        </Group>
      </div>

      {picker && <FolderPicker {...picker} />}
    </div>
  );
}

// ── Google Drive sync (group "Sync") ───────────────────────────────────────────
// Sign in with Google (OAuth, drive.file scope — synced files land in YOUR Drive),
// set the encryption passphrase, toggle auto-sync, and trigger upload/download.
// Secrets are write-only: the backend only reports whether they are saved.
// Credentials keep an explicit Save button; the auto-sync toggle autosaves.

function DriveSyncPanel({ loaded, isAuthenticated }) {
  const settings      = useStore(s => s.settings);
  const applySettings = useStore(s => s.applySettings);

  const [sync, setSync]         = useState(null);
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [passphrase, setPassphrase]     = useState('');
  const [busy, setBusy]         = useState(null);
  const [error, setError]       = useState(null);
  const [notice, setNotice]     = useState(null);
  const [editCreds, setEditCreds]     = useState(false);
  const [autoSaved, setAutoSaved]     = useState(false);

  const clientIdSet   = Boolean(settings.syncClientId);
  const secretSet     = Boolean(settings.syncClientSecretSet);
  const passSet       = Boolean(settings.syncPassphraseSet);
  const credsComplete = clientIdSet && secretSet && passSet;
  const showFields    = editCreds || !credsComplete;

  const refreshStatus = useCallback(() => {
    fetchSyncStatus().then(setSync).catch(() => setSync(null));
  }, []);

  useEffect(() => { if (isAuthenticated) refreshStatus(); }, [isAuthenticated, refreshStatus]);

  // While an upload / DB backup / restore runs, poll status so progress ticks.
  const inFlight = Boolean(sync?.uploading || sync?.dbBackupRunning || sync?.dbRestoring);
  useEffect(() => {
    if (!inFlight) return undefined;
    const id = setInterval(refreshStatus, 2000);
    return () => clearInterval(id);
  }, [inFlight, refreshStatus]);

  useEffect(() => { setClientId(settings.syncClientId ?? ''); }, [settings.syncClientId]);

  // Returning from Google: /settings?drive=connected|error&reason=…
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const drive = params.get('drive');
    if (!drive) return;
    if (drive === 'connected') setNotice('Google Drive connected.');
    else setError(`Couldn’t connect Google Drive: ${params.get('reason') || 'unknown error'}`);
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
      setError(e?.message && !/^\d+$/.test(e.message) ? e.message : 'That didn’t work — try again.');
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
      setEditCreds(false);
      setNotice('Credentials saved.');
    });
  }

  function connect() {
    return run('connect', async () => {
      window.location.href = await fetchOAuthUrl();
    });
  }

  async function toggleAuto(value) {
    setError(null);
    try {
      await applySettings({ syncEnabled: value === 'true' });
      setAutoSaved(true);
      setTimeout(() => setAutoSaved(false), 2000);
    } catch (e) {
      setError(e?.message && !/^\d+$/.test(e.message) ? e.message : 'Couldn’t save — try again.');
    }
  }

  function restoreDb() {
    const empty = Boolean(sync?.dbEmpty);
    const msg = empty
      ? 'Restore the latest backup from Drive?\n\nThis pulls the database (embeddings, cards, review state) and all vault files. A restart is recommended afterward.'
      : '⚠ This database is NOT empty.\n\nRestoring REPLACES everything with the Drive backup — current data is dropped and cannot be recovered.\n\nContinue?';
    if (!window.confirm(msg)) return;
    return run('restore', () => triggerDbRestore(!empty));
  }

  const connected = Boolean(sync?.connected);
  const queueLine = sync
    ? `${sync.pendingCount ?? 0} waiting · ${sync.doneCount ?? 0} synced · ${sync.failedCount ?? 0} failed`
      + (sync.uploading ? ` · uploading ${sync.uploadDone ?? 0}/${sync.uploadTotal ?? 0}…` : '')
    : '';
  const redirectUri = `${window.location.origin}/api/sync/oauth/callback`;
  const working = busy != null || sync?.uploading || sync?.dbBackupRunning || sync?.dbRestoring;

  return (
    <Group
      title="Sync"
      description="Encrypted backup of your vault to your own Google Drive. Google never sees plaintext."
    >
      <div className={styles.fieldRow}>
        <Field label="Status">
          <div className={styles.inline}>
            {!sync
              ? <span className={styles.value}>Unavailable — sign in first.</span>
              : <>
                  <Badge tone={connected ? 'success' : 'neutral'}>{connected ? 'Connected' : 'Not connected'}</Badge>
                  <span className={styles.value}>
                    {connected && `${sync.accountEmail || 'Google account'} · `}{queueLine}
                    {sync.quota?.usedBytes != null &&
                      ` · Drive ${fmtBytes(sync.quota.usedBytes)} of ${sync.quota.limitBytes ? fmtBytes(sync.quota.limitBytes) : '∞'} used`}
                    {!sync.encryptionConfigured && ' · passphrase not set'}
                  </span>
                </>}
          </div>
        </Field>
      </div>

      {credsComplete && !editCreds && (
        <div className={styles.fieldRow}>
          <Field label="Credentials">
            <div className={styles.inline}>
              <span className={styles.value}>OAuth client and passphrase are set ✓</span>
              <Button size="sm" variant="ghost" onClick={() => setEditCreds(true)}>Edit</Button>
            </div>
          </Field>
        </div>
      )}

      {showFields && (
        <>
          <div className={styles.fieldRow}>
            <p className={styles.note}>
              Credentials come from <code>.env</code> (<code>GOOGLE_OAUTH_CLIENT_ID/SECRET</code>,{' '}
              <code>SYNC_PASSPHRASE</code>) or the fields below. In Google Cloud Console create an OAuth
              client (type “Web application”) with this redirect URI:
            </p>
            <div className={styles.uriRow}>
              <code className={styles.uri}>{redirectUri}</code>
              <CopyButton text={redirectUri} />
            </div>
          </div>
          {(!clientIdSet || editCreds) && (
            <div className={styles.fieldRow}>
              <Field label="OAuth client ID">
                <Input
                  className={styles.mono} value={clientId} disabled={!loaded}
                  placeholder="…apps.googleusercontent.com"
                  onChange={e => setClientId(e.target.value)}
                />
              </Field>
            </div>
          )}
          {(!secretSet || editCreds) && (
            <div className={styles.fieldRow}>
              <Field label="OAuth client secret">
                <Input
                  type="password" value={clientSecret} disabled={!loaded}
                  placeholder={secretSet ? '•••••• (saved — type to replace)' : ''}
                  onChange={e => setClientSecret(e.target.value)}
                />
              </Field>
            </div>
          )}
          {(!passSet || editCreds) && (
            <div className={styles.fieldRow}>
              <Field
                label="Encryption passphrase"
                hint="Use the same passphrase on every device. Changing it makes files already on Drive unreadable until they’re re-uploaded."
              >
                <Input
                  type="password" value={passphrase} disabled={!loaded}
                  placeholder={passSet ? '•••••• (saved — type to replace)' : ''}
                  onChange={e => setPassphrase(e.target.value)}
                />
              </Field>
            </div>
          )}
          <div className={styles.fieldRow}>
            <div className={styles.actions}>
              <Button variant="primary" loading={busy === 'save'} disabled={!loaded || busy != null || !credsDirty} onClick={saveCreds}>
                Save credentials
              </Button>
              {!connected && (
                <Button
                  loading={busy === 'connect'}
                  disabled={!loaded || busy != null || !(clientIdSet && secretSet)}
                  onClick={connect}
                >
                  Connect Google Drive
                </Button>
              )}
            </div>
            {!connected && !(clientIdSet && secretSet) && (
              <p className={styles.note}>Connecting needs the client ID and secret saved first.</p>
            )}
          </div>
        </>
      )}

      {credsComplete && !editCreds && !connected && (
        <div className={styles.fieldRow}>
          <Button
            variant="primary" loading={busy === 'connect'}
            disabled={!loaded || busy != null} onClick={connect}
          >
            Connect Google Drive
          </Button>
        </div>
      )}

      <div className={styles.fieldRow}>
        <Field
          label={<>Automatic sync{autoSaved && <span className={styles.saveMark}><span className={styles.saved}>Saved ✓</span></span>}</>}
          hint="Uploads changes on a schedule (every 6 hours by default)."
        >
          <Select
            value={String(settings.syncEnabled ?? false)}
            disabled={!loaded || busy != null}
            onChange={e => toggleAuto(e.target.value)}
            options={[
              { value: 'false', label: 'Off' },
              { value: 'true', label: 'On — scheduled background uploads' },
            ]}
          />
        </Field>
      </div>

      {error  && <div className={styles.fieldRow}><p className={styles.error} role="alert">{error}</p></div>}
      {notice && !error && <div className={styles.fieldRow}><p className={styles.saved} role="status">{notice}</p></div>}

      {connected && (
        <div className={styles.fieldRow}>
          <div className={styles.actions}>
            <Button
              variant="primary"
              disabled={working}
              onClick={() => run('backup', triggerFullBackup)}
              title="Upload all vault files to Drive, then dump the database (embeddings, cards, review state) — encrypted."
            >
              {sync?.uploading
                ? `Uploading ${sync.uploadDone ?? 0}/${sync.uploadTotal ?? 0}…`
                : sync?.dbBackupRunning ? 'Backing up database…'
                : busy === 'backup' ? 'Starting…' : 'Full backup'}
            </Button>
            <Button
              disabled={working || !sync?.dbBackup?.exists}
              onClick={restoreDb}
              title="Restore the database and all vault files from Drive. For moving to a new device."
            >
              {sync?.dbRestoring ? `Restoring… ${sync.dbRestorePhase || ''}` : busy === 'restore' ? 'Starting…' : 'Restore'}
            </Button>
            <Button variant="ghost" disabled={busy != null} onClick={() => run('disconnect', disconnectDrive)}>
              {busy === 'disconnect' ? 'Disconnecting…' : 'Disconnect'}
            </Button>
          </div>
          <p className={styles.note}>
            {sync?.dbBackup?.exists
              ? `Last database backup: ${dbAgo(sync.dbBackup.lastBackupAt)}`
                + (sync.dbBackup.sizeBytes ? ` · ${fmtBytes(sync.dbBackup.sizeBytes)}` : '')
                + ` · ${sync.dbBackup.count} kept`
              : 'No database backup yet — Full backup saves embeddings, cards and review state to Drive.'}
            {sync?.dbEmpty && sync?.dbBackup?.exists &&
              ' This database looks empty — Restore will rebuild it from Drive.'}
          </p>
        </div>
      )}
    </Group>
  );
}

function fmtBytes(n) {
  if (n == null) return '0 B';
  if (n >= 1e9) return (n / 1e9).toFixed(1) + ' GB';
  if (n >= 1e6) return (n / 1e6).toFixed(1) + ' MB';
  if (n >= 1e3) return (n / 1e3).toFixed(1) + ' KB';
  return n + ' B';
}

function dbAgo(ts) {
  if (!ts) return 'never';
  const mins = Math.round((Date.now() - ts) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs} h ago`;
  return `${Math.round(hrs / 24)} d ago`;
}

