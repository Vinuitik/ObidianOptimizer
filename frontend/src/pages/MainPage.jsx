import { useEffect } from 'react';
import useStore from '../store/useStore';
import SplitLayout from '../components/templates/SplitLayout';
import LoginModal from '../components/organisms/LoginModal';

export default function MainPage() {
  const fetchNoteNames = useStore(s => s.fetchNoteNames);
  const fetchReviewNotes = useStore(s => s.fetchReviewNotes);
  const checkAuth = useStore(s => s.checkAuth);
  const showLogin = useStore(s => s.showLogin);

  useEffect(() => {
    checkAuth();
    fetchNoteNames();
    fetchReviewNotes();
  }, []);

  return (
    <>
      <SplitLayout />
      {showLogin && <LoginModal />}
    </>
  );
}
