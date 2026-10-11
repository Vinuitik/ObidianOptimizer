import useStore from '../../store/useStore';
import ReviewRating from '../molecules/ReviewRating';
import Button from '../atoms/Button';
import Card from '../molecules/Card';
import EmptyState from '../molecules/EmptyState';
import styles from './ReviewList.module.css';

export default function ReviewList() {
  const reviewNotes    = useStore(s => s.reviewNotes);
  const reviewHasMore  = useStore(s => s.reviewHasMore);
  const loadMoreReview = useStore(s => s.loadMoreReview);
  const openTab        = useStore(s => s.openTab);
  const pageSize       = useStore(s => s.settings.reviewPageSize);

  const allDone = reviewNotes.length === 0;

  if (allDone && !reviewHasMore) {
    return <EmptyState icon="check" title="All caught up" description="No notes are due for review." />;
  }

  return (
    <div className={styles.list}>
      {!allDone && (
        <Card className={styles.summaryCard}>
          <div className={styles.summaryTitle}>Today's review</div>
          <div className={styles.summaryCount}>{reviewNotes.length} notes due</div>
        </Card>
      )}

      {reviewNotes.map(({ shortName, fullPath }) => (
        <Card key={fullPath} interactive className={styles.item} onClick={() => openTab(fullPath)}>
          <span className={styles.dot} />
          <span className={styles.label}>{shortName}</span>
          <ReviewRating fullPath={fullPath} />
        </Card>
      ))}

      {allDone && reviewHasMore && (
        <div className={styles.allDone}>
          <p className={styles.allDoneText}>Batch complete!</p>
          <Button variant="primary" onClick={loadMoreReview}>
            Load next {pageSize} →
          </Button>
        </div>
      )}
    </div>
  );
}
