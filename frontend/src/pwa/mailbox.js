// Write-back: push queued events to the encrypted Drive mailbox (_mailbox/), which the
// server drains on its next boot (DRIVE_OFFLINE_SYNC_ARCH §3c). Works with the laptop OFF —
// that's the whole point. P3 pushes `grade` events; other kinds stay in the outbox for the
// server-direct path until P5 extends both sides.
import { getCreds } from './setup';
import { getAccessToken, findOrCreateFolder, driveCreateFile } from './drive';
import { deriveKey, encryptText } from './crypto';
import { getOutbox, deleteFromOutbox } from './db';

export async function pushMailbox() {
  const creds = await getCreds();
  if (!creds?.driveFolderId) return { pushed: 0 };

  const all = await getOutbox();
  // Server consume handles grade + assignment (P3/P4). Other kinds stay for the
  // server-direct path until their consumer lands.
  const sendable = all.filter(e => e.kind === 'grade' || e.kind === 'assignment');
  if (!sendable.length) return { pushed: 0 };

  const token = await getAccessToken(creds);
  const folderId = await findOrCreateFolder(token, '_mailbox', creds.driveFolderId);
  const key = await deriveKey(creds.passphrase);

  const events = sendable.map(e => {
    if (e.kind === 'assignment') {
      return { kind: 'assignment', assignmentId: e.assignmentId, notePath: e.notePath, answers: e.answers, eventId: e.eventId, ts: e.ts };
    }
    return { kind: 'grade', notePath: e.notePath, band: e.band, eventId: e.eventId, ts: e.ts };
  });
  const enc = await encryptText(key, JSON.stringify({ deviceId: creds.deviceId, events }));

  // Name sorts by ts on the server (device-<ts>-<seq>) so events replay in order.
  const seq = Math.floor(Math.random() * 1e6);
  const name = `${creds.deviceId}-${Date.now()}-${seq}.enc`;
  await driveCreateFile(token, { name, parents: [folderId], appProperties: { device_id: creds.deviceId } }, enc);

  for (const e of sendable) await deleteFromOutbox(e.id);
  return { pushed: events.length };
}
