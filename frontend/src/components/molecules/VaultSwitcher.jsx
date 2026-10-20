import useVaults from '../../vault/useVaults';
import { switchVault } from '../../api/vaults';
import Select from '../atoms/Select';
import styles from './VaultSwitcher.module.css';

// Top-bar vault picker (desktop NavBar + pwa/TopBar). Hidden with a single vault.
// Change -> PUT /api/vaults/active -> full page reload (switchVault).
export default function VaultSwitcher() {
  const { vaults, active } = useVaults();
  const choices = (vaults || []).filter(v => !v.archived_at);
  if (!active || choices.length < 2) return null;
  return (
    <Select
      className={styles.select}
      aria-label="Active vault"
      value={String(active.id)}
      options={choices.map(v => ({ value: String(v.id), label: v.name }))}
      onChange={e => switchVault(Number(e.target.value)).catch(() => {})}
    />
  );
}
