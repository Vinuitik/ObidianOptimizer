import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import VaultsPanel from './VaultsPanel';

vi.mock('../api/notes', () => ({ fetchHostChildren: vi.fn() }));
vi.mock('../api/vaults', () => ({
  fetchVaults: vi.fn(), registerVault: vi.fn(), renameVault: vi.fn(),
  archiveVault: vi.fn(), restoreVault: vi.fn(), switchVault: vi.fn(), deleteVault: vi.fn(),
}));
import { fetchVaults, registerVault, switchVault, deleteVault } from '../api/vaults';

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

  it('delete needs the vault name typed; never offered for active/primary', async () => {
    deleteVault.mockResolvedValue({ status: 'QUEUED' });
    render(<VaultsPanel isAuthenticated />);
    await screen.findByText('Work');
    // vault 1 is active -> no Delete; vaults 2 and 3 are eligible
    expect(screen.getAllByText('Delete')).toHaveLength(2);
    fireEvent.click(within(screen.getByTestId('vault-2')).getByText('Delete'));
    const confirm = screen.getByText('Delete vault');
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Type the vault name to confirm'), { target: { value: 'wor' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Type the vault name to confirm'), { target: { value: 'Work' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(deleteVault).toHaveBeenCalledWith(2));
  });

  it('shows queued and in-progress deletion state without a Delete button', async () => {
    fetchVaults.mockResolvedValue([
      LIST[0],
      { ...LIST[1], deletion_status: 'QUEUED' },
      { ...LIST[2], archived_at: null, deletion_status: 'RUNNING' },
    ]);
    render(<VaultsPanel isAuthenticated />);
    expect(await screen.findByText('Deletion queued')).toBeInTheDocument();
    expect(screen.getByText('Deleting…')).toBeInTheDocument();
    expect(screen.queryByText('Delete')).toBeNull();
  });
});
