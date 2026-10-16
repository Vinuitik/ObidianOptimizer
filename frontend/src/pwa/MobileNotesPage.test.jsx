import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import useStore from '../store/useStore';
import MobileNotesPage from './MobileNotesPage';

const tree = { children: { 'a.md': { type: 'file', fullPath: 'a.md' } }, loaded: true };
function setup(extra = {}) {
  useStore.setState({
    isAuthenticated: true, tree, fetchRootChildren: vi.fn(),
    currentNotePath: null, currentNoteRaw: '', tabs: [], activeTabIndex: -1, ...extra,
  });
  render(<MemoryRouter><MobileNotesPage /></MemoryRouter>);
}

describe('MobileNotesPage tap', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('opens reader when tapped note is already current', async () => {
    const openTab = vi.fn(async () => {});
    setup({ openTab, currentNotePath: 'a.md', currentNoteRaw: '# Hi' });
    // reader auto-hidden initially (viewing=false) -> tree shown
    fireEvent.click(screen.getByText('a'));
    await waitFor(() => expect(screen.getByText('← Notes')).toBeTruthy());
  });

  it('opens reader after openTab for a new note', async () => {
    const openTab = vi.fn(async (p) => useStore.setState({ currentNotePath: p, currentNoteRaw: 'x' }));
    setup({ openTab });
    fireEvent.click(screen.getByText('a'));
    await waitFor(() => expect(screen.getByText('← Notes')).toBeTruthy());
  });

  it('toasts on failed fetch', async () => {
    const openTab = vi.fn(async () => { throw new Error('timeout'); });
    const showToast = vi.fn();
    setup({ openTab, showToast });
    fireEvent.click(screen.getByText('a'));
    await waitFor(() => expect(showToast).toHaveBeenCalled());
    expect(screen.queryByText('← Notes')).toBeNull();
  });
});
