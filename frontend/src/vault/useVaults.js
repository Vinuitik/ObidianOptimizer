import { useState, useEffect, useCallback } from 'react';
import { fetchVaults } from '../api/vaults';

// Vault registry list for UI ({ vaults, active, reload }). `active` = the row flagged active.
export default function useVaults() {
  const [vaults, setVaults] = useState(null);
  const reload = useCallback(() =>
    fetchVaults().then(setVaults).catch(() => setVaults(prev => prev)), []);
  useEffect(() => { reload(); }, [reload]);
  const active = vaults?.find(v => v.active) ?? null;
  return { vaults, active, reload };
}
