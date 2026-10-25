import { describe, it, expect } from 'vitest';
import { readVaultPath, vaultPathFits, sha1Hex } from './vaultPath';

describe('readVaultPath', () => {
  it('reads legacy single key', () => expect(readVaultPath({ vault_path: 'a/b.md' })).toBe('a/b.md'));
  it('joins chunks (multibyte safe)', () =>
    expect(readVaultPath({ vault_path_n: '2', vault_path_0: 'notes/Привет—', vault_path_1: '日本.md' }))
      .toBe('notes/Привет—日本.md'));
  it('null on missing/incomplete', () => {
    expect(readVaultPath(undefined)).toBeNull();
    expect(readVaultPath({})).toBeNull();
    expect(readVaultPath({ vault_path_n: '2', vault_path_0: 'a' })).toBeNull();
  });
});

describe('vaultPathFits', () => {
  it('boundary 114 bytes', () => {
    expect(vaultPathFits('a'.repeat(114))).toBe(true);
    expect(vaultPathFits('a'.repeat(115))).toBe(false);
    expect(vaultPathFits('—'.repeat(38))).toBe(true);
    expect(vaultPathFits('—'.repeat(39))).toBe(false);
  });
});

describe('sha1Hex', () => {
  it('matches known vector', async () =>
    expect(await sha1Hex('abc')).toBe('a9993e364706816aba3e25717850c26c9cd0d89d'));
});
