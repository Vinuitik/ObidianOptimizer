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
  const grades = all.filter(e => e.kind === 'grade');
  if (!grades.length) return { pushed: 0 };

  const token = await getAccessToken(creds);
  const folderId = await findOrCreateFolder(token, '_mailbox', creds.driveFolderId);
  const key = await deriveKey(creds.passphrase);

  const events = grades.map(g => ({
    kind: 'grade', notePath: g.notePath, band: g.band, eventId: g.eventId, ts: g.ts,
  }));
  const enc = await encryptText(key, JSON.stringify({ deviceId: creds.deviceId, events }));

  // Name sorts by ts on the server (device-<ts>-<seq>) so events replay in order.
  const seq = Math.floor(Math.random() * 1e6);
  const name = `${creds.deviceId}-${Date.now()}-${seq}.enc`;
  await driveCreateFile(token, { name, parents: [folderId], appProperties: { device_id: creds.deviceId } }, enc);

  for (const g of grades) await deleteFromOutbox(g.id);
  return { pushed: events.length };
}
