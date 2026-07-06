// Phone-side mirror of the server's VaultEncryptionService (sync/VaultEncryptionService.java).
// MUST stay byte-compatible with it — a mismatch makes every Drive file undecryptable.
//
//   key    = PBKDF2-HMAC-SHA256(passphrase, salt="ObsidianSyncSalt", 310_000) → 256-bit AES
//   cipher = AES-256-GCM, 12B IV, 128-bit tag
//   plain  = gzip'd before encryption
//   wire   = [12B IV][GCM ciphertext + 16B tag]
//
// All primitives are native in Chrome Android (WebCrypto + Compression Streams). Cross-
// compat with the Java output is verified at runtime by the P1 "read a real note" proof.

const SALT = new TextEncoder().encode('ObsidianSyncSalt');
const ITERATIONS = 310_000;
const IV_BYTES = 12;
const TAG_BITS = 128;

function asU8(buf) {
  if (buf instanceof Uint8Array) return buf;
  if (buf instanceof ArrayBuffer) return new Uint8Array(buf);
  return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
}

async function streamBytes(bytes, transform) {
  const readable = new Response(asU8(bytes)).body.pipeThrough(transform);
  return new Uint8Array(await new Response(readable).arrayBuffer());
}
const gzip   = (bytes) => streamBytes(bytes, new CompressionStream('gzip'));
const gunzip = (bytes) => streamBytes(bytes, new DecompressionStream('gzip'));

// Derive the AES key once (PBKDF2 at 310k iters is deliberately slow) and reuse it.
export async function deriveKey(passphrase) {
  const base = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: SALT, iterations: ITERATIONS, hash: 'SHA-256' },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

// [IV][ct+tag] → gunzip → bytes. Throws on a wrong key/passphrase (GCM tag check).
export async function decrypt(key, encBytes) {
  const b = asU8(encBytes);
  const iv = b.subarray(0, IV_BYTES);
  const ct = b.subarray(IV_BYTES);
  const compressed = await crypto.subtle.decrypt({ name: 'AES-GCM', iv, tagLength: TAG_BITS }, key, ct);
  return gunzip(new Uint8Array(compressed));
}

// bytes → gzip → AES-GCM → [IV][ct+tag]. Exact inverse of decrypt (matches the Java writer).
export async function encrypt(key, plainBytes) {
  const compressed = await gzip(plainBytes);
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv, tagLength: TAG_BITS }, key, compressed));
  const out = new Uint8Array(IV_BYTES + ct.length);
  out.set(iv, 0);
  out.set(ct, IV_BYTES);
  return out;
}

export const decryptText = async (key, encBytes) => new TextDecoder().decode(await decrypt(key, encBytes));
export const encryptText = (key, str) => encrypt(key, new TextEncoder().encode(str));
