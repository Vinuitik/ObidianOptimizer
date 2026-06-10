import useStore from '../../store/useStore';
import ReviewRating from '../molecules/ReviewRating';
import styles from './ReviewList.module.css';

export default function ReviewList() {
  const reviewNotes    = useStore(s => s.reviewNotes);
  const reviewHasMore  = useStore(s => s.reviewHasMore);
  const loadMoreReview = useStore(s => s.loadMoreReview);
  const openTab        = useStore(s => s.openTab);
  const pageSize       = useStore(s => s.settings.reviewPageSize);

  const allDone = reviewNotes.length === 0;

  if (allDone && !reviewHasMore) {
    return <p className={styles.empty}>No notes due for review.</p>;
  }

  return (
    <div className={styles.list}>
      {!allDone && (
        <div className={styles.summaryCard}>
          <div>
            <div className={styles.summaryTitle}>Today's review</div>
            <div className={styles.summaryCount}>{reviewNotes.length} notes due</div>
          </div>
        </div>
      )}

      {reviewNotes.map(({ shortName, fullPath }) => (
        <div key={fullPath} className={styles.item} onClick={() => openTab(fullPath)}>
          <span className={styles.dot} />
          <span className={styles.label}>{shortName}</span>
          <ReviewRating fullPath={fullPath} />
        </div>
      ))}

      {allDone && reviewHasMore && (
        <div className={styles.allDone}>
          <p className={styles.allDoneText}>Batch complete!</p>
          <button className={styles.loadMore} onClick={loadMoreReview}>
            Load next {pageSize} →
          </button>
        </div>
      )}
    </div>
  );
}
