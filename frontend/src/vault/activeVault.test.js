import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  getActiveVaultId, dbNameFor, mediaCacheNameFor, mediaPrefix, setActiveVaultId, vaultFetch, bootVault,
} from './activeVault';
import { mediaUrl } from '../utils/media';
import { vaultMediaUrl } from '../utils/noteMedia';

beforeEach(() => localStorage.clear());

describe('vault naming', () => {
  it('vault 1 keeps legacy names, others get a suffix', () => {
    expect(dbNameFor(1)).toBe('obsopt-offline');
    expect(dbNameFor(3)).toBe('obsopt-offline-v3');
    expect(mediaCacheNameFor(1)).toBe('obsopt-media');
    expect(mediaCacheNameFor(2)).toBe('obsopt-media-v2');
  });
  it('defaults to vault 1 with nothing stored', () => {
    expect(getActiveVaultId()).toBe(1);
    expect(dbNameFor()).toBe('obsopt-offline');
  });
  it('setActiveVaultId drops vault-scoped session keys when the id changes', async () => {
    localStorage.setItem('obsOpt_reviewSession', 'x');
    await setActiveVaultId(2);
    expect(getActiveVaultId()).toBe(2);
    expect(localStorage.getItem('obsOpt_reviewSession')).toBeNull();
    localStorage.setItem('obsOpt_reviewSession', 'y');
    await setActiveVaultId(2);
    expect(localStorage.getItem('obsOpt_reviewSession')).toBe('y');
  });
});

describe('media URL prefix', () => {
  it('is bare for vault 1 and /<id>/ for others', async () => {
    expect(mediaPrefix('vault-media')).toBe('/vault-media/');
    expect(mediaUrl('a.png')).toBe('/vault-media/images/a.png');
    await setActiveVaultId(2);
    expect(mediaUrl('a.png')).toBe('/vault-media/2/images/a.png');
    expect(vaultMediaUrl('resources/x/y.pdf')).toBe('/vault-media/2/x/y.pdf');
    expect(vaultMediaUrl('_workspace/v.mp4')).toBe('/workspace/2/v.mp4');
  });
});

describe('vaultFetch', () => {
  it('adds X-Vault-Id to /api calls but not to /api/vaults or non-api', async () => {
    await setActiveVaultId(3);
    const base = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    const f = vaultFetch(base);
    await f('/api/notes');
    expect(base.mock.calls[0][1].headers.get('X-Vault-Id')).toBe('3');
    await f('/api/vaults/active', { method: 'PUT' });
    expect(base.mock.calls[1][1].headers).toBeUndefined();
    await f('/vault-media/x.png');
    expect(base.mock.calls[2][1]).toBeUndefined();
  });

  it('on 409 vault_mismatch adopts the server id and reloads', async () => {
    const body = JSON.stringify({ error: 'vault_mismatch', activeVaultId: 2, requestedVaultId: 1 });
    const base = vi.fn().mockResolvedValue(new Response(body, { status: 409 }));
    const reload = vi.fn();
    await vaultFetch(base, { reload })('/api/review');
    expect(getActiveVaultId()).toBe(2);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('does not reload on an unrelated 409', async () => {
    const base = vi.fn().mockResolvedValue(new Response('{"error":"dup"}', { status: 409 }));
    const reload = vi.fn();
    await vaultFetch(base, { reload })('/api/x');
    expect(reload).not.toHaveBeenCalled();
  });
});

describe('bootVault', () => {
  it('adopts the active vault from GET /vaults and reports a change', async () => {
    const mk = () => Promise.resolve(new Response(JSON.stringify([{ id: 1, active: false }, { id: 2, active: true }])));
    const fetchImpl = vi.fn(mk);
    expect((await bootVault(fetchImpl)).changed).toBe(true);
    expect(getActiveVaultId()).toBe(2);
    expect((await bootVault(fetchImpl)).changed).toBe(false);
  });
  it('keeps the stored id when the server is unreachable', async () => {
    await setActiveVaultId(2);
    const r = await bootVault(vi.fn().mockRejectedValue(new TypeError('offline')));
    expect(r.changed).toBe(false);
    expect(getActiveVaultId()).toBe(2);
  });
});
