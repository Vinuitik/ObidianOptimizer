import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import MobileLayout from './MobileLayout';
import CapturePage from './CapturePage';
import SyncPage from './SyncPage';
import ReviewPage from '../pages/ReviewPage';
import LearnPage from '../pages/LearnPage';

// The installed PWA — deliberately NARROW (not the whole website). Three jobs:
//   • Review   — do flashcards (offline once the offline seam lands), grades sync back.
//   • Learn    — triage the ingest inbox (online; offline learn lane is the next step).
//   • Capture  — Android share-sheet target → ingest pipeline (+ manual paste).
//   • Sync     — download the review subset for offline; replay the outbox.
// Everything else (full note editor, folder tree, search, dashboard, settings) lives
// on the full site, reached by opening the link in a browser. See ResponsiveApp.
//
// Leaf reuse: ReviewPage / LearnPage are the SAME components the desktop renders — a
// fix there lands on both. Only the shell (MobileLayout + BottomNav) and Sync/Capture
// are PWA-specific. /share-target has no React route (the service worker 303s it to
// /capture?shared=…).
export default function PwaApp() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MobileLayout />}>
          <Route path="/" element={<Navigate to="/review" replace />} />
          <Route path="/review" element={<ReviewPage />} />
          <Route path="/learn" element={<LearnPage />} />
          <Route path="/capture" element={<CapturePage />} />
          <Route path="/settings" element={<SyncPage />} />
          <Route path="*" element={<Navigate to="/review" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
