// Vault registry REST (backend vaults/FLOWS.md). /api/vaults** is exempt from the X-Vault-Id check.
// Rows are raw DB columns: { id, name, host_path, container_path, active, is_primary, archived_at }.
import { setActiveVaultId } from '../vault/activeVault';

async function req(url, options = {}) {
  const res = await fetch(`/api/vaults${url}`, {
    credentials: 'same-origin', cache: 'no-store',
    headers: { 'Content-Type': 'application/json' }, ...options,
  });
  if (!res.ok) {
    let msg = String(res.status);
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  return res.json();
}

export const fetchVaults = () => req('');
// 202: the wrapper recreates containers (~20s). Poll fetchVaults() until it answers again.
export const registerVault = (name, hostPath) =>
  req('', { method: 'POST', body: JSON.stringify({ name, hostPath }) });
export const renameVault = (id, name) =>
  req(`/${id}`, { method: 'PATCH', body: JSON.stringify({ name }) });
export const archiveVault = (id) => req(`/${id}/archive`, { method: 'POST' });
export const restoreVault = (id) => req(`/${id}/restore`, { method: 'POST' });

// Switch the server's active vault, remember it for sync readers + the SW, then reload so every
// store / IDB handle / cached URL is rebuilt for the new vault.
export async function switchVault(id, reload = () => location.reload()) {
  await req('/active', { method: 'PUT', body: JSON.stringify({ id }) });
  await setActiveVaultId(id);
  reload();
}
