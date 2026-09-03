// Write-back: push queued events to the encrypted Drive mailbox (_mailbox/), which the
// server drains on its next boot (DRIVE_OFFLINE_SYNC_ARCH §3c). Works with the laptop OFF —
// that's the whole point. `capture`/`captureText`/`captureFile` included: when the device is
// online but the SERVER is unreachable, Drive is more durable than trusting the local
// IndexedDB outbox alone (a cleared cache / storage-pressure eviction / reinstall loses it;
// Drive doesn't). `captureFile` is base64'd into the same JSON+encrypt envelope as everything
// else — see MAILBOX_FILE_MAX_BYTES below for the size the server will still consume.
import { getCreds } from './setup';
import { getAccessToken, findOrCreateFolder, driveCreateFile } from './drive';
import { deriveKey, encryptText } from './crypto';
import { getOutbox, deleteFromOutbox } from './db';
import { captureSentNotifyEnabled, notifyCaptureSent } from './captureSentNotify';

const CAPTURE_KINDS = new Set(['capture', 'captureText', 'captureFile']);

// Above this (raw bytes, before base64's ~33% inflation), a shared file stays outbox-only —
// server-direct retry only, same as before this existed. Keep in sync with the server's
// mailbox.file.max-bytes (MailboxConsumeService) — a file under this that the server still
// rejects just gets dropped there, so the two should agree.
export const MAILBOX_FILE_MAX_BYTES = 100 * 1024 * 1024; // 100MB

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => resolve(String(reader.result).split(',')[1] ?? '');
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

export async function pushMailbox() {
  const creds = await getCreds();
  if (!creds?.driveFolderId) return { pushed: 0 };

  const all = await getOutbox();
  // Server consume handles all of these kinds (MailboxConsumeService).
  const KINDS = new Set(['grade', 'assignment', 'file', 'discard', 'acknowledge', 'flag', 'capture', 'captureText', 'captureFile']);
  const sendable = all.filter(e => KINDS.has(e.kind) &&
    !(e.kind === 'captureFile' && e.blob?.size > MAILBOX_FILE_MAX_BYTES));
  if (!sendable.length) return { pushed: 0 };

  const token = await getAccessToken(creds);
  const folderId = await findOrCreateFolder(token, '_mailbox', creds.driveFolderId);
  const key = await deriveKey(creds.passphrase);

  // Send each event as-is (minus the local IDB autoincrement id) — the server dispatches
  // by `kind` and reads whatever fields that kind carries. captureFile's Blob doesn't
  // survive JSON.stringify, so swap it for a base64 string under the same event.
  const events = await Promise.all(sendable.map(async ({ id, blob, ...ev }) => {
    if (ev.kind === 'captureFile' && blob) return { ...ev, fileBase64: await blobToBase64(blob) };
    return ev;
  }));
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
