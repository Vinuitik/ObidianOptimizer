import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import MobileLayout from './MobileLayout';
import MobileNotesPage from './MobileNotesPage';
import MobileSearchPage from './MobileSearchPage';
import CapturePage from './CapturePage';
import ReviewPage from '../pages/ReviewPage';
import SettingsPage from '../pages/SettingsPage';

// Mobile root. Same store, same leaf components — only the shell + navigation
// differ from desktop. Routes mirror the bottom-nav tabs in BottomNav.jsx.
//
// Note: /share-target is handled entirely by the service worker (it 303-redirects
// to /capture?shared=…), so there is no React route for it.
export default function MobileApp() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MobileLayout />}>
          <Route path="/" element={<MobileNotesPage />} />
          <Route path="/review" element={<ReviewPage />} />
          <Route path="/search" element={<MobileSearchPage />} />
          <Route path="/capture" element={<CapturePage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
