import { useEffect, useState } from 'react';
import { fetchHostChildren } from '../api/notes';
import {
  fetchVaults, registerVault, renameVault, archiveVault, restoreVault, switchVault, deleteVault,
} from '../api/vaults';
import useVaults from '../vault/useVaults';
import FolderPicker from '../components/organisms/FolderPicker';
import Button from '../components/atoms/Button';
import Badge from '../components/atoms/Badge';
import Input from '../components/atoms/Input';
import Card from '../components/molecules/Card';
import Field from '../components/molecules/Field';
import styles from './SettingsPage.module.css';

const baseName = p => (p || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || '';
const sleep = ms => new Promise(r => setTimeout(r, ms));

// Host-fs browser view -> FolderPicker's { current, parent, dirs } shape.
const loadHostDir = async (path) => {
  const r = await fetchHostChildren(path);
  return { current: r.path, parent: r.parent, dirs: r.dirs };
};

// POST /vaults returns 202 while the host helper recreates containers (~20s, API briefly down).
// Wait a beat, then poll GET /vaults until it answers again.
export async function waitUntilReachable({ tries = 45, delayMs = 2000, initialMs = 5000 } = {}) {
  await sleep(initialMs);
  for (let i = 0; i < tries; i++) {
    try { return await fetchVaults(); } catch { await sleep(delayMs); }
  }
  throw new Error('The app did not come back in time — refresh in a minute.');
}

// Settings ▸ Vaults: list, register (host folder browser + downtime confirm), switch, rename,
// archive/restore. Drive sync is configured per vault in the Sync group below (acts on the
// ACTIVE vault — the server's sync endpoints have no vault parameter).
export default function VaultsPanel({ isAuthenticated, waitOpts }) {
  const { vaults, active, reload } = useVaults();
  const [picker, setPicker]   = useState(false);
  const [name, setName]       = useState('');
  const [hostPath, setHostPath] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy]       = useState(null);
  const [error, setError]     = useState(null);
  const [editing, setEditing] = useState(null);   // { id, name }
  const [deleting, setDeleting] = useState(null); // { id, name, typed }

  // Keep refreshing while a deletion job is queued/running so the badge settles by itself.
  const deletionPending = (vaults || []).some(v => v.deletion_status === 'QUEUED' || v.deletion_status === 'RUNNING');
  useEffect(() => {
    if (!deletionPending) return undefined;
    const t = setInterval(() => { reload(); }, 3000);
    return () => clearInterval(t);
  }, [deletionPending, reload]);

  async function run(label, fn) {
    setBusy(label); setError(null);
    try { await fn(); await reload(); }
    catch (e) { setError(e.message || 'Something went wrong.'); }
    finally { setBusy(null); }
  }

  async function doRegister() {
    setConfirming(false);
    setBusy('register'); setError(null);
    try {
      await registerVault(name.trim() || baseName(hostPath), hostPath.trim());
      setBusy('restarting');
      await waitUntilReachable(waitOpts);
      setName(''); setHostPath('');
      await reload();
    } catch (e) { setError(e.message || 'Could not add the vault.'); }
    finally { setBusy(null); }
  }

  return (
    <Card as="section" className={styles.group} aria-label="Vaults">
      <Card.Header
        title="Vaults"
        subtitle={active ? `Active: ${active.name}. Each vault has its own notes, cards and Drive sync.` : 'Separate note collections.'}
      />
      <div className={styles.fields}>
        {(vaults || []).map(v => (
          <div key={v.id} className={styles.fieldRow} data-testid={`vault-${v.id}`}>
            <div className={styles.inline}>
              {editing?.id === v.id ? (
                <Input
                  aria-label="Vault name" value={editing.name}
                  onChange={e => setEditing({ id: v.id, name: e.target.value })}
                  onKeyDown={e => {
                    if (e.key === 'Enter' && editing.name.trim()) run('rename', async () => { await renameVault(v.id, editing.name); setEditing(null); });
                    if (e.key === 'Escape') setEditing(null);
                  }}
                />
              ) : (
                <strong>{v.name}</strong>
              )}
              {v.active && <Badge tone="success">Active</Badge>}
              {v.archived_at && <Badge tone="neutral">Archived</Badge>}
              {v.deletion_status === 'QUEUED' && <Badge tone="neutral">Deletion queued</Badge>}
              {v.deletion_status === 'RUNNING' && <Badge tone="neutral">Deleting…</Badge>}
              {v.deletion_status === 'FAILED' && <Badge tone="danger">Deletion failed</Badge>}
            </div>
            <span className={styles.note}>{v.host_path || v.container_path}</span>
            <div className={styles.inline}>
              {!v.active && !v.archived_at && (
                <Button size="sm" disabled={!isAuthenticated || busy} onClick={() => run('switch', () => switchVault(v.id))}>Switch to</Button>
              )}
              {editing?.id === v.id ? (
                <Button size="sm" variant="ghost" onClick={() => setEditing(null)}>Cancel</Button>
              ) : (
                <Button size="sm" variant="ghost" disabled={!isAuthenticated} onClick={() => setEditing({ id: v.id, name: v.name })}>Rename</Button>
              )}
              {!v.active && (v.archived_at
                ? <Button size="sm" variant="ghost" disabled={!isAuthenticated || busy} onClick={() => run('restore', () => restoreVault(v.id))}>Restore</Button>
                : <Button size="sm" variant="ghost" disabled={!isAuthenticated || busy} onClick={() => run('archive', () => archiveVault(v.id))}>Archive</Button>)}
              {!v.active && !v.is_primary && (!v.deletion_status || v.deletion_status === 'FAILED') && (
                <Button size="sm" variant="ghost" disabled={!isAuthenticated || busy} onClick={() => setDeleting({ id: v.id, name: v.name, typed: '' })}>Delete</Button>
              )}
            </div>
            {deleting?.id === v.id && (
              <div role="alertdialog" aria-label="Confirm delete vault" className={styles.note}>
                <p>Deletes this vault&apos;s notes, cards, tracks and settings from the app (a backup is written first). Files in the folder on disk are never touched. Type <strong>{v.name}</strong> to confirm.</p>
                <Input
                  aria-label="Type the vault name to confirm" value={deleting.typed}
                  onChange={e => setDeleting({ ...deleting, typed: e.target.value })}
                />
                <div className={styles.inline}>
                  <Button
                    size="sm" variant="primary" disabled={deleting.typed !== v.name || Boolean(busy)}
                    onClick={() => run('delete', async () => { await deleteVault(v.id); setDeleting(null); })}
                  >Delete vault</Button>
                  <Button size="sm" variant="ghost" onClick={() => setDeleting(null)}>Cancel</Button>
                </div>
              </div>
            )}
            {!v.active && !v.archived_at && (
              <span className={styles.note}>Drive sync: switch to this vault to see or change it.</span>
            )}
          </div>
        ))}

        <div className={styles.fieldRow}>
          <Field label="Add a vault" hint="Pick a folder on your computer. Registering restarts the app for about 20 seconds.">
            <Input
              aria-label="New vault name" placeholder="Name (optional)" value={name}
              onChange={e => setName(e.target.value)}
            />
          </Field>
          <Input
            aria-label="New vault folder" className={styles.mono} placeholder="Folder on this computer"
            value={hostPath} onChange={e => setHostPath(e.target.value)}
          />
          <div className={styles.pathActions}>
            <Button size="sm" disabled={!isAuthenticated} onClick={() => setPicker(true)}>Browse…</Button>
            <Button size="sm" variant="primary" disabled={!isAuthenticated || !hostPath.trim() || Boolean(busy)} onClick={() => setConfirming(true)}>Add vault</Button>
          </div>
          {confirming && (
            <div role="alertdialog" aria-label="Confirm add vault" className={styles.note}>
              <p>Adding a vault restarts the app (~20 seconds of downtime). Notes you are editing right now should be saved first.</p>
              <div className={styles.inline}>
                <Button size="sm" variant="primary" onClick={doRegister}>Add and restart</Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>Cancel</Button>
              </div>
            </div>
          )}
          {busy === 'restarting' && <p className={styles.note} role="status">Restarting the app… this takes about 20 seconds.</p>}
          {error && <p className={styles.error} role="alert">{error}</p>}
        </div>
      </div>

      {picker && (
        <FolderPicker
          title="Select vault folder"
          loadPath={loadHostDir}
          initialPath={null}
          confirmLabel="Use this folder"
          onSelect={(p) => { setHostPath(p); if (!name.trim()) setName(baseName(p)); setPicker(false); }}
          onClose={() => setPicker(false)}
        />
      )}
    </Card>
  );
}
