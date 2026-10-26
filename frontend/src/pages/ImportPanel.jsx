import { useRef, useState } from 'react';
import { importNotes } from '../api/importNotes';
import Button from '../components/atoms/Button';
import styles from './SettingsPage.module.css';

const ACCEPT = '.zip,.md,.markdown,.txt,.pdf,.docx';

// Settings > Import: Notion "Markdown & CSV" zip, zipped Obsidian/markdown folder, or loose
// .md/.txt/.pdf/.docx. Everything lands in _inbox/Imported/ for review (backend importer/FLOWS.md).
export default function ImportPanel() {
  const inputRef = useRef(null);
  const [files, setFiles] = useState([]);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  async function run() {
    setBusy(true); setError(null); setResult(null);
    try {
      setResult(await importNotes(files));
      setFiles([]);
      if (inputRef.current) inputRef.current.value = '';
    } catch (e) {
      setError(e.message || 'Import failed.');
    } finally {
      setBusy(false);
    }
  }

  const skipped = result?.skipped ?? [];
  return (
    <section id="import" className={styles.section} aria-label="Import">
      <h2 className={styles.sectionTitle}>Import</h2>
      <p className={styles.sectionDesc}>
        Bring in notes from Notion (export as “Markdown &amp; CSV”, upload the .zip), an Obsidian vault
        (zip the folder), or individual .md, .txt, .pdf and .docx files. Nothing touches your existing
        notes — imports go to the Inbox for you to review.
      </p>
      <div className={styles.fields}>
        <div className={styles.fieldRow}>
          <input
            ref={inputRef} type="file" multiple accept={ACCEPT} aria-label="Files to import"
            disabled={busy} onChange={e => setFiles(Array.from(e.target.files ?? []))}
          />
          <Button onClick={run} disabled={busy || files.length === 0}>
            {busy ? 'Importing…' : 'Import'}
          </Button>
        </div>
        {busy && (
          <div className={styles.fieldRow}>
            <progress aria-label="Import progress" />
            <p className={styles.note}>Uploading and importing — large zips can take a few minutes. Keep this tab open.</p>
          </div>
        )}
        {error && <div className={styles.fieldRow}><p className={styles.error} role="alert">{error}</p></div>}
        {result && (
          <div className={styles.fieldRow} role="status">
            <div>
              <p>
                Imported {result.notes} notes, {result.attachments} attachments
                {result.queuedForIngest > 0 && `, ${result.queuedForIngest} sent to the ingest queue`}
                {skipped.length > 0 && `, ${skipped.length} skipped`}.
              </p>
              {result.folders?.length > 0 && <p className={styles.note}>Find them in the Inbox under {result.folders.join(', ')}.</p>}
              {skipped.length > 0 && (
                <ul>
                  {skipped.map((s, i) => <li key={i}>{s.path} — {s.reason}</li>)}
                </ul>
              )}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
