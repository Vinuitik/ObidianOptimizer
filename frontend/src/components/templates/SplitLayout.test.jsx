import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SplitLayout from './SplitLayout';
import useStore from '../../store/useStore';

vi.mock('../organisms/FolderTree', () => ({ default: () => <div /> }));
vi.mock('../organisms/ReviewList', () => ({ default: () => <div /> }));
vi.mock('../../editor/CmEditor', () => ({ default: () => <div data-testid="cm" /> }));
vi.mock('../molecules/TabBar', () => ({ default: () => null }));
vi.mock('../molecules/ReviewRating', () => ({ default: () => null }));

const flushSave = vi.fn();
const toggleMutable = vi.fn();
const commitTitle = vi.fn();

function open({ mutable = true, dirty = false, saveStatus = null } = {}) {
  useStore.setState({
    currentNotePath: '/vault/Note.md',
    currentNoteRaw: 'old',
    pendingRaw: dirty ? 'new' : 'old',
    pendingTitle: 'Note',
    pendingFiles: {},
    isMutable: mutable,
    saveStatus,
    titleFocusNonce: 0,
    leftCollapsed: false,
    rightCollapsed: true,
    reviewNotes: [],
    settings: { flashcardsEnabled: true },
    flushSave, toggleMutable, commitTitle,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  flushSave.mockResolvedValue();
});

describe('SplitLayout note editing', () => {
  it('Ctrl+S flushes the save', () => {
    open({ dirty: true });
    render(<SplitLayout />);
    fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    expect(flushSave).toHaveBeenCalledTimes(1);
  });

  it('Ctrl+E toggles edit / reading view', () => {
    open();
    render(<SplitLayout />);
    fireEvent.keyDown(window, { key: 'e', ctrlKey: true });
    expect(toggleMutable).toHaveBeenCalledTimes(1);
  });

  it('has ONE toggle button and no Save / Cancel', () => {
    open();
    render(<SplitLayout />);
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Reading view' }));
    expect(toggleMutable).toHaveBeenCalledTimes(1);
  });

  it('reading view renders MarkdownView of pending content with an Edit button', () => {
    open({ mutable: false, dirty: true });
    useStore.setState({ pendingRaw: '# Hello pending' });
    render(<SplitLayout />);
    expect(screen.getByRole('heading', { name: 'Hello pending' })).toBeInTheDocument();
    expect(screen.queryByTestId('cm')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    expect(toggleMutable).toHaveBeenCalledTimes(1);
  });

  it('status: Saving… while dirty/saving, Saved after', () => {
    open({ dirty: true });
    const { unmount } = render(<SplitLayout />);
    expect(screen.getByText('Saving…')).toBeInTheDocument();
    unmount();
    open({ dirty: false, saveStatus: 'saved' });
    render(<SplitLayout />);
    expect(screen.getByText('Saved')).toBeInTheDocument();
  });

  it('failed save shows a retry button that saves again', () => {
    open({ dirty: true, saveStatus: 'error' });
    render(<SplitLayout />);
    fireEvent.click(screen.getByRole('button', { name: 'Save failed — retry' }));
    expect(flushSave).toHaveBeenCalledTimes(1);
  });

  it('window blur and hidden tab flush the save', () => {
    open({ dirty: true });
    render(<SplitLayout />);
    fireEvent.blur(window);
    expect(flushSave).toHaveBeenCalledTimes(1);
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    fireEvent(document, new Event('visibilitychange'));
    expect(flushSave).toHaveBeenCalledTimes(2);
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });

  it('title is a 1-row auto-growing field; rename only on blur / Enter, never per keystroke', () => {
    open();
    render(<SplitLayout />);
    const input = screen.getByLabelText('Note title');
    expect(input.tagName).toBe('TEXTAREA');
    expect(input.getAttribute('rows')).toBe('1');
    input.focus();
    fireEvent.change(input, { target: { value: 'Renamed' } });
    expect(commitTitle).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    input.blur();
    expect(commitTitle).toHaveBeenCalledWith('Renamed');
  });

  it('titleFocusNonce focuses and selects the title', async () => {
    open();
    render(<SplitLayout />);
    useStore.setState({ titleFocusNonce: 1 });
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Note title')));
  });
});
