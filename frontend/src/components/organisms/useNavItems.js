import useStore from '../../store/useStore';

// Single source of truth for the site's routes — shared by the desktop NavBar links
// and the phone MobileTabBar. To add/rename a page: edit NAV_ITEMS only.
// `primary` = top bar (desktop) + phone bottom bar; the rest = avatar menu / phone "More".
// Dashboard, Failures, Get App live in Settings → System (routes unchanged).
export const NAV_ITEMS = [
  { to: '/review',    label: 'Review',    icon: 'clock',    primary: true, flashcardsOnly: true },
  { to: '/sources',   label: 'Sources',   icon: 'plus',     primary: true },
  { to: '/learn',     label: 'Learn',     icon: 'sparkle',  primary: true },
  { to: '/notes',     label: 'Notes',     icon: 'file',     primary: true },
  { to: '/settings',  label: 'Settings' },
];

export default function useNavItems() {
  // Flashcards off → review-list runs inline on Notes, so the Review tab disappears.
  const flashcardsEnabled = useStore(s => s.settings.flashcardsEnabled ?? true);
  return NAV_ITEMS
    .filter(it => !it.flashcardsOnly || flashcardsEnabled);
}
