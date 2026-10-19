import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SplitLayout from './SplitLayout';
import useStore from '../../store/useStore';

vi.mock('../organisms/FolderTree', () => ({ default: () => <div /> }));
vi.mock('../organisms/ReviewList', () => ({ default: () => <div /> }));
vi.mock('../organisms/NewNoteForm', () => ({ default: () => <div /> }));
vi.mock('../molecules/TabBar', () => ({ default: () => null }));
vi.mock('../molecules/ReviewRating', () => ({ default: () => null }));

const syncNote = vi.fn();
const cancelEdit = vi.fn();
const showToast = vi.fn();

function openEditing({ dirty }) {
  useStore.setState({
    currentNotePath: '/vault/Note.md',
    currentNoteRaw: 'old',
    pendingRaw: dirty ? 'new' : 'old',
    pendingTitle: 'Note',
    pendingFiles: {},
    isMutable: true,
    centerMode: 'view',
    leftCollapsed: false,
    rightCollapsed: true,
    reviewNotes: [],
    settings: { flashcardsEnabled: true },
    syncNote, cancelEdit, showToast,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  syncNote.mockResolvedValue();
});

describe('SplitLayout note editing', () => {
  it('Ctrl+S saves', async () => {
    openEditing({ dirty: true });
    render(<SplitLayout />);
    fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    await waitFor(() => expect(syncNote).toHaveBeenCalledTimes(1));
  });

  it('shows Unsaved changes when dirty', () => {
    openEditing({ dirty: true });
    render(<SplitLayout />);
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
  });

  it('Cancel with unsaved changes asks inline instead of discarding', () => {
    openEditing({ dirty: true });
    render(<SplitLayout />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(cancelEdit).not.toHaveBeenCalled();
    expect(screen.getByText('Discard unsaved changes?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(cancelEdit).toHaveBeenCalledTimes(1);
  });

  it('Cancel with no changes cancels immediately', () => {
    openEditing({ dirty: false });
    render(<SplitLayout />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(cancelEdit).toHaveBeenCalledTimes(1);
  });

  it('save errors are caught and toasted', async () => {
    syncNote.mockRejectedValue(new Error('disk full'));
    openEditing({ dirty: true });
    render(<SplitLayout />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(showToast).toHaveBeenCalled());
    expect(showToast.mock.calls[0][0].tone).toBe('error');
    expect(screen.getByText('Save failed')).toBeInTheDocument();
  });
});
