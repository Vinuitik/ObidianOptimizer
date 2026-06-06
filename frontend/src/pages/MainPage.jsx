import { useEffect } from 'react';
import useStore from '../store/useStore';
import SplitLayout from '../components/templates/SplitLayout';
import LoginModal from '../components/organisms/LoginModal';

export default function MainPage() {
  const fetchRootChildren  = useStore(s => s.fetchRootChildren);
  const fetchNoteNames     = useStore(s => s.fetchNoteNames);
  const initReviewSession  = useStore(s => s.initReviewSession);
  const checkAuth          = useStore(s => s.checkAuth);
  const showLogin          = useStore(s => s.showLogin);

  useEffect(() => {
    checkAuth();
    // Fast: only root-level folder contents — UI shows immediately
    fetchRootChildren();
    // Background: full noteIndex for wiki-link resolution
    fetchNoteNames();
    // Review: respects localStorage offset + new-day reset
    initReviewSession();
  }, []);

  return (
    <>
      <SplitLayout />
      {showLogin && <LoginModal />}
    </>
  );
}
