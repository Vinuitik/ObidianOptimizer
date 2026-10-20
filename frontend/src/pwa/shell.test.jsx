import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import useStore from '../store/useStore';

let updateCb = null;
const applyUpdate = vi.fn();
vi.mock('./registerSW', () => ({
  onUpdateAvailable: (cb) => { updateCb = cb; return () => { updateCb = null; }; },
  isUpdateReady: () => false,
  applyUpdate: (...a) => applyUpdate(...a),
  checkForUpdate: vi.fn(),
  reloadApp: vi.fn(),
}));
vi.mock('./syncStatus', async (orig) => {
  const real = await orig();
  return { ...real, default: () => ({ state: 'failed', label: '2 failed', tone: 'danger' }) };
});

import BottomNav, { TABS } from './BottomNav';
import TopBar, { titleFor } from './TopBar';
import UpdateBanner from './UpdateBanner';
import { deriveSyncStatus, agoShort } from './syncStatus';

const wrap = (ui, path = '/review') => render(<MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>);

describe('bottom nav', () => {
  beforeEach(() => {
    useStore.setState({ isAuthenticated: false, reviewNotes: [], settings: { tracksEnabled: true } });
  });

  it('is Review, Learn, Capture, Notes - no Sync tab', () => {
    expect(TABS.map(t => t.to)).toEqual(['/review', '/learn', '/sources', '/notes']);
    wrap(<BottomNav />);
    const labels = screen.getAllByRole('link').map(a => a.textContent);
    expect(labels).toEqual(['Review', 'Learn', 'Sources', 'Notes']);
  });

  it('shows due-count badge on Review from the store', () => {
    useStore.setState({ reviewNotes: [{}, {}, {}] });
    wrap(<BottomNav />);
    expect(screen.getByLabelText('3 due')).toBeTruthy();
  });
});

describe('top bar', () => {
  it('shows the page title, not the brand', () => {
    expect(titleFor('/learn')).toBe('Learn');
    expect(titleFor('/settings')).toBe('Sync');
    wrap(<TopBar />, '/notes');
    expect(screen.getByRole('heading', { name: 'Notes' })).toBeTruthy();
  });

  it('sync chip links to /sync', () => {
    wrap(<TopBar />);
    const chip = screen.getByRole('link', { name: /Sync status: 2 failed/ });
    expect(chip.getAttribute('href')).toBe('/sync');
  });
});

describe('deriveSyncStatus', () => {
  const now = 10_000_000_000;
  it('offline wins', () => {
    expect(deriveSyncStatus({ online: false, stage: 'x', failedCount: 3 }).state).toBe('offline');
  });
  it('syncing beats failed', () => {
    expect(deriveSyncStatus({ online: true, stage: 'x', failedCount: 3 }).state).toBe('syncing');
  });
  it('failed shows N', () => {
    expect(deriveSyncStatus({ online: true, failedCount: 2 }).label).toBe('2 failed');
  });
  it('synced shows relative time', () => {
    const s = deriveSyncStatus({ online: true, lastSync: now - 2 * 3600_000, now });
    expect(s).toMatchObject({ state: 'synced', label: 'Synced 2h ago' });
  });
  it('never synced', () => {
    expect(deriveSyncStatus({ online: true }).state).toBe('never');
    expect(agoShort(null)).toBeNull();
  });
});

describe('pwa pages compile', () => {
  it('imports every routed page', async () => {
    const mods = await Promise.all([
      import('./MobileApp'), import('./CapturePage'), import('./SyncPage'),
      import('./MobileNotesPage'), import('./MobileSearchPage'),
    ]);
    mods.forEach(m => expect(typeof m.default).toBe('function'));
  }, 60000);
});

describe('update banner', () => {
  it('is hidden until an update is waiting, then persists with Reload', () => {
    render(<UpdateBanner />);
    expect(screen.queryByText('Update ready')).toBeNull();
    act(() => updateCb());
    expect(screen.getByText('Update ready')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    expect(applyUpdate).toHaveBeenCalled();
  });
});
