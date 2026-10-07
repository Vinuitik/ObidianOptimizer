import useStore from '../../store/useStore';

// Single source of truth for the site's routes — shared by the desktop NavBar links
// and the phone MobileTabBar. To add/rename a page: edit NAV_ITEMS only.
// `primary` = lives in the phone bottom bar; everything else goes under "More".
export const NAV_ITEMS = [
  { to: '/',          label: 'Notes',     icon: 'file',     primary: true },
  { to: '/learn',     label: 'Learn',     icon: 'sparkle',  primary: true },
  { to: '/review',    label: 'Review',    icon: 'clock',    primary: true, flashcardsOnly: true },
  { to: '/tracks',    label: 'Tracks',    icon: 'track',    primary: true, tracksOnly: true },
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/failures',  label: 'Failures' },
  { to: '/get-app',   label: 'Get App' },
  { to: '/settings',  label: 'Settings' },
];

export default function useNavItems() {
  // Flashcards off → review-list runs inline on Notes, so the Review tab disappears.
  const flashcardsEnabled = useStore(s => s.settings.flashcardsEnabled ?? true);
  // Tracks off → hidden from nav, still reachable at /tracks directly.
  const tracksEnabled = useStore(s => s.settings.tracksEnabled ?? true);
  return NAV_ITEMS
    .filter(it => !it.flashcardsOnly || flashcardsEnabled)
    .filter(it => !it.tracksOnly || tracksEnabled);
}
