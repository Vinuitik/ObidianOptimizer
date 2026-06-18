import { useEffect } from 'react';
import useStore from '../store/useStore';
import SplitLayout from '../components/templates/SplitLayout';

export default function MainPage() {
  const fetchRootChildren  = useStore(s => s.fetchRootChildren);
  const fetchNoteNames     = useStore(s => s.fetchNoteNames);
  const initReviewSession  = useStore(s => s.initReviewSession);
  const loadSettings       = useStore(s => s.loadSettings);

  useEffect(() => {
    // Load settings first so reviewPageSize is correct before initReviewSession
    loadSettings().then(() => initReviewSession());
    fetchRootChildren();
    fetchNoteNames();
  }, []);

  return <SplitLayout />;
}
