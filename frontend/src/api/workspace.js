const BASE = '/api';

export async function fetchWorkspaceFiles() {
  const res = await fetch(`${BASE}/workspace/files`, { credentials: 'same-origin' });
  if (!res.ok) throw new Error(res.status);
  return res.json();
}
