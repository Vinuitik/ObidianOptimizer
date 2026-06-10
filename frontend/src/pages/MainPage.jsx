import { useEffect } from 'react';
import useStore from '../store/useStore';
import SplitLayout from '../components/templates/SplitLayout';
import LoginModal from '../components/organisms/LoginModal';
import Toast from '../components/atoms/Toast';

export default function MainPage() {
  const fetchRootChildren  = useStore(s => s.fetchRootChildren);
  const fetchNoteNames     = useStore(s => s.fetchNoteNames);
  const initReviewSession  = useStore(s => s.initReviewSession);
  const checkAuth          = useStore(s => s.checkAuth);
  const loadSettings       = useStore(s => s.loadSettings);
  const showLogin          = useStore(s => s.showLogin);

  useEffect(() => {
    // Load settings first so reviewPageSize is correct before initReviewSession
    loadSettings().then(() => initReviewSession());
    checkAuth();
    fetchRootChildren();
    fetchNoteNames();
  }, []);

  return (
    <>
      <SplitLayout />
      {showLogin && <LoginModal />}
      <Toast />
    </>
  );
}
