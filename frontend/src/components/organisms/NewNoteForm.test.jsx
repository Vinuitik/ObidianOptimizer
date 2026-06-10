import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import NewNoteForm from './NewNoteForm';

// ── Store mock ────────────────────────────────────────────────────────────────

const mockCreateNote  = vi.fn();
const mockCancelNewNote = vi.fn();

let mockNewNoteFolder = '/vault/FolderA';
let mockNewNoteName   = 'MyNote';

vi.mock('../../store/useStore', () => ({
  default: vi.fn((selector) => selector({
    newNoteFolder: mockNewNoteFolder,
    newNoteName:   mockNewNoteName,
    createNote:    mockCreateNote,
    cancelNewNote: mockCancelNewNote,
  })),
}));

vi.mock('../atoms/Button', () => ({
  default: ({ children, onClick, type }) => (
    <button type={type ?? 'button'} onClick={onClick}>{children}</button>
  ),
}));

beforeEach(() => {
  vi.clearAllMocks();
  mockNewNoteFolder = '/vault/FolderA';
  mockNewNoteName   = 'MyNote';
});

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('NewNoteForm', () => {
  it('displays the folder name as a hint', () => {
    render(<NewNoteForm />);
    expect(screen.getByText(/Creating in: FolderA/i)).toBeInTheDocument();
  });

  it('shows vault root hint when newNoteFolder is vault root', () => {
    mockNewNoteFolder = null;
    render(<NewNoteForm />);
    expect(screen.getByText(/Creating in: vault root/i)).toBeInTheDocument();
  });

  it('clicking Create calls createNote with folder and name', () => {
    render(<NewNoteForm />);
    fireEvent.click(screen.getByText('Create'));
    expect(mockCreateNote).toHaveBeenCalledWith('/vault/FolderA', 'MyNote');
  });

  it('Create does not call createNote when newNoteName is blank', () => {
    mockNewNoteName = '   ';
    render(<NewNoteForm />);
    fireEvent.click(screen.getByText('Create'));
    expect(mockCreateNote).not.toHaveBeenCalled();
  });

  it('clicking Cancel calls cancelNewNote', () => {
    render(<NewNoteForm />);
    fireEvent.click(screen.getByText('Cancel'));
    expect(mockCancelNewNote).toHaveBeenCalled();
  });
});
