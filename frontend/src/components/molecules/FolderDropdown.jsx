import useStore from '../../store/useStore';
import styles from './FolderDropdown.module.css';

function deriveFolders(noteIndex, vaultRoot) {
  const seen = new Set();
  const folders = [];
  for (const fullPath of noteIndex.values()) {
    const parts = fullPath.split(/[/\\]/);
    const sep   = fullPath.includes('\\') ? '\\' : '/';
    const parent = parts.slice(0, -1).join(sep);
    if (parent && !seen.has(parent)) {
      seen.add(parent);
      folders.push({ path: parent, display: parts[parts.length - 2] ?? parent });
    }
  }
  folders.sort((a, b) => a.display.localeCompare(b.display));
  if (vaultRoot && !seen.has(vaultRoot)) {
    folders.unshift({ path: vaultRoot, display: '(vault root)' });
  }
  return folders;
}

export default function FolderDropdown({ value, onChange, className }) {
  const noteIndex = useStore(s => s.noteIndex);
  const vaultRoot = useStore(s => s.vaultRoot);
  const folders   = deriveFolders(noteIndex, vaultRoot);

  return (
    <select
      className={`${styles.select} ${className ?? ''}`}
      value={value ?? ''}
      onChange={e => onChange(e.target.value)}
    >
      {!value && <option value="" disabled>Select folder…</option>}
      {folders.map(f => (
        <option key={f.path} value={f.path}>{f.display}</option>
      ))}
    </select>
  );
}
