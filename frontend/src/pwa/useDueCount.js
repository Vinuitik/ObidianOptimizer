import { useEffect } from 'react';
import useStore from '../store/useStore';

// Due count for the Review tab badge: the review queue already in the store
// (reviewNotes — same list ReviewPage shows). Primed once after sign-in via the SAME
// initReviewSession ReviewPage runs, so no new endpoint. 0 → no badge.
export default function useDueCount() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const count = useStore(s => s.reviewNotes?.length ?? 0);
  const init = useStore(s => s.initReviewSession);
  useEffect(() => {
    if (isAuthenticated && count === 0) Promise.resolve(init?.()).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated]);
  return count;
}
