// POST /api/import (multipart, field "files"). Returns
// { notes, attachments, queuedForIngest, folders: [..], skipped: [{ path, reason }] }.
// Plain fetch on purpose: installVaultFetch() adds X-Vault-Id; do NOT set Content-Type (boundary).
export async function importNotes(files) {
  const fd = new FormData();
  for (const f of files) fd.append('files', f, f.name);
  const res = await fetch('/api/import', {
    method: 'POST', body: fd, credentials: 'same-origin', cache: 'no-store',
  });
  if (!res.ok) {
    let msg = `Import failed (${res.status})`;
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  return res.json();
}
