import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('./pages/MainPage', () => ({ default: () => <div data-testid="notes" /> }));
vi.mock('./pages/ReviewPage', () => ({ default: () => <div data-testid="review" /> }));
vi.mock('./pages/LearnPage', () => ({ default: () => <div /> }));
vi.mock('./pages/SourcesPage', () => ({ default: () => <div data-testid="sources" /> }));
vi.mock('./pages/SettingsPage', () => ({ default: () => <div /> }));
vi.mock('./pages/DashboardPage', () => ({ default: () => <div /> }));
vi.mock('./pages/GetAppPage', () => ({ default: () => <div /> }));
vi.mock('./pages/PipelineFailuresPage', () => ({ default: () => <div /> }));
vi.mock('./pages/StyleguidePage', () => ({ default: () => <div /> }));
vi.mock('./components/organisms/NavBar', () => ({ default: () => null }));
vi.mock('./components/organisms/MobileTabBar', () => ({ default: () => null }));
vi.mock('./components/organisms/LoginModal', () => ({ default: () => null }));
vi.mock('./components/organisms/SyncBanner', () => ({ default: () => null }));
vi.mock('./components/organisms/QuitGuard', () => ({ default: () => null }));
vi.mock('./components/atoms/Toast', () => ({ default: () => null }));
vi.mock('./pwa/offlineApi', () => ({ flushOutbox: vi.fn().mockResolvedValue({ sent: 0 }), OUTBOX_RETRY_MS: 1e9 }));
vi.mock('./pwa/mailbox', () => ({ pushMailbox: vi.fn() }));
vi.mock('./pwa/setup', () => ({ hasCreds: vi.fn().mockResolvedValue(false) }));
vi.mock('./pwa/quitNotify', () => ({ showLocalNotification: vi.fn() }));
vi.mock('./utils/useMediaQuery', () => ({ useIsMobile: () => false }));
vi.mock('./store/useStore', () => {
  const state = { checkAuth: vi.fn(), showLogin: false, showToast: vi.fn(), initRsvpWpmFromDrive: vi.fn() };
  return { default: vi.fn(sel => sel(state)) };
});

import App from './App';

describe('App routes', () => {
  it('default route / lands on Review', () => {
    window.history.pushState({}, '', '/');
    render(<App />);
    expect(screen.getByTestId('review')).toBeTruthy();
    expect(window.location.pathname).toBe('/review');
  });

  it('/sources mounts the Sources page, /notes the Notes page', () => {
    window.history.pushState({}, '', '/sources');
    const { unmount } = render(<App />);
    expect(screen.getByTestId('sources')).toBeTruthy();
    unmount();
    window.history.pushState({}, '', '/notes');
    render(<App />);
    expect(screen.getByTestId('notes')).toBeTruthy();
  });
});
