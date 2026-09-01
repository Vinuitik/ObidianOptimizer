// Write-back: push queued events to the encrypted Drive mailbox (_mailbox/), which the
// server drains on its next boot (DRIVE_OFFLINE_SYNC_ARCH §3c). Works with the laptop OFF —
// that's the whole point. `capture`/`captureText` included: when the phone is online but
// the SERVER is unreachable, Drive is more durable than trusting the local IndexedDB outbox
// alone (a cleared cache / storage-pressure eviction / reinstall loses it; Drive doesn't).
// `captureFile` stays outbox-only — a Blob doesn't fit this module's encryptText(JSON) shape
// without a base64 detour, and file shares are rarer than link/text captures.
import { getCreds } from './setup';
import { getAccessToken, findOrCreateFolder, driveCreateFile } from './drive';
import { deriveKey, encryptText } from './crypto';
import { getOutbox, deleteFromOutbox } from './db';
import { captureSentNotifyEnabled, notifyCaptureSent } from './captureSentNotify';

const CAPTURE_KINDS = new Set(['capture', 'captureText']);

export async function pushMailbox() {
  const creds = await getCreds();
  if (!creds?.driveFolderId) return { pushed: 0 };

  const all = await getOutbox();
  // Server consume handles these kinds (MailboxConsumeService). Others (captureFile) stay
  // for the server-direct path.
  const KINDS = new Set(['grade', 'assignment', 'file', 'discard', 'acknowledge', 'flag', 'capture', 'captureText']);
  const sendable = all.filter(e => KINDS.has(e.kind));
  if (!sendable.length) return { pushed: 0 };

  const token = await getAccessToken(creds);
  const folderId = await findOrCreateFolder(token, '_mailbox', creds.driveFolderId);
  const key = await deriveKey(creds.passphrase);

  // Send each event as-is (minus the local IDB autoincrement id) — the server dispatches
  // by `kind` and reads whatever fields that kind carries.
  const events = sendable.map(({ id, ...ev }) => ev);
  const enc = await encryptText(key, JSON.stringify({ deviceId: creds.deviceId, events }));

  // Name sorts by ts on the server (device-<ts>-<seq>) so events replay in order.
  const seq = Math.floor(Math.random() * 1e6);
  const name = `${creds.deviceId}-${Date.now()}-${seq}.enc`;
  await driveCreateFile(token, { name, parents: [folderId], appProperties: { device_id: creds.deviceId } }, enc);

  const notifyOn = await captureSentNotifyEnabled();
  for (const e of sendable) {
    await deleteFromOutbox(e.id);
    if (notifyOn && CAPTURE_KINDS.has(e.kind)) {
      notifyCaptureSent(e, 'sent to Drive — will process once the server drains it');
    }
  }
  return { pushed: events.length };
}
