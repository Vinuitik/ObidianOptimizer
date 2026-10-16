import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor, act } from '@testing-library/react';
import MobileLayout from './MobileLayout';

// Regression coverage for three outbox-resync fixes that all live in this component's
// reconnect effect (frontend/src/pwa/MobileLayout.jsx):
//   c0b38b2 — flush on visibilitychange, not just the online event
//   fdfb5ee — also retry on a short interval (OUTBOX_RETRY_MS) while the tab stays open
//   62990af — only toast "N synced" when a flush actually lands items, not just on accept

const state = {
  checkAuth: vi.fn().mockResolvedValue(undefined),
  setShowLogin: vi.fn(),
  isAuthenticated: true,
  showLogin: false,
  showToast: vi.fn(),
  syncStage: null,
  initRsvpWpmFromDrive: vi.fn(),
};
vi.mock('../store/useStore', () => {
  const useStoreMock = vi.fn(sel => sel(state));
  useStoreMock.getState = () => state;
  return { default: useStoreMock };
});

vi.mock('./useOffline', () => ({ default: vi.fn(() => true) }));

const mockFlushOutbox = vi.fn();
vi.mock('./offlineApi', () => ({
  flushOutbox: (...a) => mockFlushOutbox(...a),
  setDriveMode: vi.fn(),
  OUTBOX_RETRY_MS: 60 * 1000,
}));

const mockHasCreds = vi.fn();
vi.mock('./setup', () => ({ hasCreds: (...a) => mockHasCreds(...a) }));

vi.mock('./mailbox', () => ({ pushMailbox: vi.fn().mockResolvedValue({ pushed: 0 }) }));
vi.mock('./autoSync', () => ({ maybeAutoSync: vi.fn() }));
vi.mock('./quitNotify', () => ({ armQuitNotify: vi.fn(), showLocalNotification: vi.fn() }));
vi.mock('./syncStageLabel', () => ({ stageText: vi.fn(() => '') }));
vi.mock('../components/organisms/LoginModal', () => ({ default: () => null }));
vi.mock('../components/organisms/RouteErrorBoundary', () => ({ default: ({ children }) => children }));
vi.mock('../components/atoms/Toast', () => ({ default: () => null }));
vi.mock('./BottomNav', () => ({ default: () => null }));
vi.mock('./TopBar', () => ({ default: () => null }));
vi.mock('./UpdateBanner', () => ({ default: () => null }));
vi.mock('react-router-dom', () => ({ Outlet: () => null }));

function setVisibility(value) {
  Object.defineProperty(document, 'visibilityState', { value, configurable: true });
}

beforeEach(() => {
  vi.clearAllMocks();
  mockHasCreds.mockResolvedValue(false);
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
  setVisibility('visible');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('MobileLayout — outbox resync', () => {
  it('a visibilitychange to visible re-flushes the outbox (backgrounded tab missed the online event)', async () => {
    mockFlushOutbox.mockResolvedValue({ sent: 0 });
    render(<MobileLayout />);
    await waitFor(() => expect(mockFlushOutbox).toHaveBeenCalledTimes(1)); // mount sync

    mockFlushOutbox.mockResolvedValue({ sent: 3 });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    await waitFor(() => expect(mockFlushOutbox).toHaveBeenCalledTimes(2));
    expect(state.showToast).toHaveBeenCalledWith('Back online — 3 synced');
  });

  it('a stale queue drains on the retry interval alone, with no visibility/online transition', async () => {
    vi.useFakeTimers();
    mockFlushOutbox.mockResolvedValue({ sent: 0 });
    render(<MobileLayout />);
    await act(async () => { await Promise.resolve(); }); // flush mount-time microtasks
    expect(mockFlushOutbox).toHaveBeenCalledTimes(1);

    mockFlushOutbox.mockResolvedValue({ sent: 1 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60 * 1000);
    });

    expect(mockFlushOutbox).toHaveBeenCalledTimes(2);
    expect(state.showToast).toHaveBeenCalledWith('Back online — 1 synced');
  });

  it('a flush that lands nothing does not falsely announce a sync', async () => {
    mockFlushOutbox.mockResolvedValue({ sent: 0 });
    render(<MobileLayout />);
    await waitFor(() => expect(mockFlushOutbox).toHaveBeenCalledTimes(1));

    expect(state.showToast).not.toHaveBeenCalled();
  });
});
