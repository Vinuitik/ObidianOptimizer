import { useEffect } from 'react';
import useStore from '../store/useStore';
import SplitLayout from '../components/templates/SplitLayout';

export default function MainPage() {
  const fetchNoteNames = useStore(s => s.fetchNoteNames);
  const fetchReviewNotes = useStore(s => s.fetchReviewNotes);

  useEffect(() => {
    fetchNoteNames();
    fetchReviewNotes();
  }, []);

  return <SplitLayout />;
}
