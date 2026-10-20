import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import MobileLayout from './MobileLayout';
import CapturePage from './CapturePage';
import SyncPage from './SyncPage';
import ReviewPage from '../pages/ReviewPage';
import LearnPage from '../pages/LearnPage';
import SourcesPage from '../pages/SourcesPage';
import MobileNotesPage from './MobileNotesPage';
import MobileSearchPage from './MobileSearchPage';
import PageTransition from '../components/motion/PageTransition';

// Enter-only fadeRise per route (each route element remounts on navigation).
const page = el => <PageTransition>{el}</PageTransition>;

// The installed PWA — deliberately NARROW (not the whole website). Jobs:
//   • Review   — do flashcards (offline once the offline seam lands), grades sync back.
//   • Learn    — triage the ingest inbox (online; offline learn lane is the next step).
//   • Capture  — Android share-sheet target → ingest pipeline (+ manual paste).
//   • Tracks   — curricula + subscriptions; same component the desktop renders.
//   • Notes    — read-only vault browser (+ /search, linked from Notes).
//   • Sync     — NOT a tab: the TopBar sync chip opens /sync (download for offline; outbox).
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
          <Route path="/review" element={page(<ReviewPage />)} />
          <Route path="/learn" element={page(<LearnPage />)} />
          <Route path="/sources" element={page(<SourcesPage />)} />
          <Route path="/capture" element={page(<CapturePage />)} />
          <Route path="/tracks" element={<Navigate to="/learn" replace />} />
          <Route path="/notes" element={page(<MobileNotesPage />)} />
          <Route path="/search" element={page(<MobileSearchPage />)} />
          <Route path="/sync" element={page(<SyncPage />)} />
          {/* old links / bookmarks */}
          <Route path="/settings" element={<Navigate to="/sync" replace />} />
          <Route path="*" element={<Navigate to="/review" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
