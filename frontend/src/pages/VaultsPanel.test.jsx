import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import VaultsPanel from './VaultsPanel';

vi.mock('../api/notes', () => ({ fetchHostChildren: vi.fn() }));
vi.mock('../api/vaults', () => ({
  fetchVaults: vi.fn(), registerVault: vi.fn(), renameVault: vi.fn(),
  archiveVault: vi.fn(), restoreVault: vi.fn(), switchVault: vi.fn(),
}));
import { fetchVaults, registerVault, switchVault } from '../api/vaults';

const LIST = [
  { id: 1, name: 'NewLife', host_path: '/home/me/NewLife', container_path: '/vault', active: true },
  { id: 2, name: 'Work', host_path: '/home/me/Work', container_path: '/vaults/2', active: false },
  { id: 3, name: 'Old', host_path: '/home/me/Old', container_path: '/vaults/3', active: false, archived_at: 'x' },
];
beforeEach(() => { vi.clearAllMocks(); fetchVaults.mockResolvedValue(LIST); });

describe('VaultsPanel', () => {
  it('lists vaults with active and archived marks', async () => {
    render(<VaultsPanel isAuthenticated />);
    expect(await screen.findByText('Work')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Archived')).toBeInTheDocument();
    expect(screen.getByText('/home/me/Work')).toBeInTheDocument();
  });

  it('switches to an inactive vault', async () => {
    render(<VaultsPanel isAuthenticated />);
    fireEvent.click(await screen.findByText('Switch to'));
    await waitFor(() => expect(switchVault).toHaveBeenCalledWith(2));
  });

  it('warns about downtime before registering, then POSTs', async () => {
    registerVault.mockResolvedValue({ id: 4 });
    render(<VaultsPanel isAuthenticated waitOpts={{ initialMs: 0, delayMs: 0, tries: 2 }} />);
    fireEvent.change(await screen.findByLabelText('New vault folder'), { target: { value: '/home/me/New' } });
    fireEvent.click(screen.getByText('Add vault'));
    expect(screen.getByText(/20 seconds of downtime/)).toBeInTheDocument();
    expect(registerVault).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Add and restart'));
    await waitFor(() => expect(registerVault).toHaveBeenCalledWith('New', '/home/me/New'));
  });

  it('offers no delete, only archive', async () => {
    render(<VaultsPanel isAuthenticated />);
    await screen.findByText('Work');
    expect(screen.queryByText('Delete')).toBeNull();
    expect(screen.queryByText('Delete vault')).toBeNull();
    expect(screen.getByText('Archive')).toBeInTheDocument();
  });
});
