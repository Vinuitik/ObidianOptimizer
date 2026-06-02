import useStore from '../../store/useStore';
import styles from './ReviewList.module.css';

export default function ReviewList() {
  const reviewNotes = useStore(s => s.reviewNotes);
  const openNote = useStore(s => s.openNote);

  if (!reviewNotes.length) {
    return <p className={styles.empty}>No notes due for review.</p>;
  }

  return (
    <div className={styles.list}>
      {reviewNotes.map(({ shortName, fullPath }) => (
        <button
          key={fullPath}
          className={styles.item}
          onClick={() => openNote(fullPath)}
          title={shortName}
        >
          {shortName}
        </button>
      ))}
    </div>
  );
}
