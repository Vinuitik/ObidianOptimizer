import { useMemo } from 'react';
import { parseLinks } from '../../utils/inboxParse';
import styles from './LinksPanel.module.css';

// Right panel of the review: the links the pipeline injected, laid out so their ORDER is
// obvious and verifiable (INGESTION_V2_FLOWS §5/§7). SEQUENTIAL is the deterministic
// chronological chain (prev → THIS → next) — the guarantee that you never read a later
// chapter before an earlier one. SEMANTIC ("Related") are the ANN neighbours. Clicking a
// link calls `onOpen(stem)` (optional) so the reviewer can jump to a neighbour.
export default function LinksPanel({ item, onOpen }) {
  const { sequence, related } = useMemo(
    () => parseLinks(item?.content || ''),
    [item],
  );
  if (!item) return <div className={styles.empty}>—</div>;

  const thisTitle = item.title || 'This note';
  const hasAny = sequence.prev || sequence.next || related.length;

  return (
    <div className={styles.panel}>
      <div className={styles.section}>
        <h4 className={styles.h}>Chronology</h4>
        <ol className={styles.chain}>
          <li className={styles.chainItem}>
            {sequence.prev
              ? <LinkChip stem={sequence.prev} onOpen={onOpen} />
              : <span className={styles.start}>— start of source</span>}
          </li>
          <li className={`${styles.chainItem} ${styles.current}`}>▸ {thisTitle}</li>
          <li className={styles.chainItem}>
            {sequence.next
              ? <LinkChip stem={sequence.next} onOpen={onOpen} />
              : <span className={styles.start}>end of source —</span>}
          </li>
        </ol>
      </div>

      <div className={styles.section}>
        <h4 className={styles.h}>Related</h4>
        {related.length === 0
          ? <p className={styles.none}>No semantic matches yet.</p>
          : (
            <ul className={styles.related}>
              {related.map(stem => (
                <li key={stem}><LinkChip stem={stem} onOpen={onOpen} /></li>
              ))}
            </ul>
          )}
      </div>

      {!hasAny && <p className={styles.none}>No links on this note.</p>}
    </div>
  );
}

function LinkChip({ stem, onOpen }) {
  return (
    <button
      type="button"
      className={styles.chip}
      onClick={() => onOpen?.(stem)}
      disabled={!onOpen}
      title={stem}
    >
      [[{stem}]]
    </button>
  );
}
