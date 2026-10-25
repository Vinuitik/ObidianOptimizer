// Drive caps each appProperty at 124 bytes (key+value), so DriveService.uploadFile
// (VaultPathProps.java) stores long vault paths as vault_path_0..n-1 chunks + vault_path_n
// + vault_path_sha1; short paths keep the single legacy `vault_path` key.
const LIMIT = 124;

export function readVaultPath(props) {
  if (!props) return null;
  if (props.vault_path != null) return props.vault_path;
  const n = parseInt(props.vault_path_n, 10);
  if (!Number.isInteger(n) || n <= 0 || n > 30) return null;
  let out = '';
  for (let i = 0; i < n; i++) {
    const c = props[`vault_path_${i}`];
    if (c == null) return null;
    out += c;
  }
  return out;
}

export function vaultPathFits(path) {
  return 'vault_path'.length + new TextEncoder().encode(path).length <= LIMIT;
}

export async function sha1Hex(s) {
  const d = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(s));
  return Array.from(new Uint8Array(d), b => b.toString(16).padStart(2, '0')).join('');
}
