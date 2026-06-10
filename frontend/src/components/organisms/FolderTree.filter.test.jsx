import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import FolderTree from './FolderTree';

// ── Store mock ────────────────────────────────────────────────────────────────

const mockStartNewNote    = vi.fn();
const mockOpenTab         = vi.fn();
const mockDeleteNote      = vi.fn();
const mockMoveNote        = vi.fn();
const mockCreateFolder    = vi.fn();
const mockFetchChildrenOf = vi.fn();

const TREE_WITH_FILES = {
  type: 'folder',
  fullPath: '/vault',
  loaded: true,
  children: {
    'MatchingNote.md': { type: 'file', fullPath: '/vault/MatchingNote.md' },
    'OtherNote.md':   { type: 'file', fullPath: '/vault/OtherNote.md' },
    // Folder pre-loaded so filter can expand without fetching
    'FolderA': {
      type: 'folder',
      fullPath: '/vault/FolderA',
      loaded: true,
      children: {
        'MatchingChild.md': { type: 'file', fullPath: '/vault/FolderA/MatchingChild.md' },
      },
    },
    'EmptyFolder': {
      type: 'folder',
      fullPath: '/vault/EmptyFolder',
      loaded: true,
      children: {},
    },
  },
};

vi.mock('../../store/useStore', () => ({
  default: vi.fn((selector) => selector({
    tree:            TREE_WITH_FILES,
    vaultRoot:       '/vault',
    currentNotePath: null,
    startNewNote:    mockStartNewNote,
    openTab:         mockOpenTab,
    deleteNote:      mockDeleteNote,
    moveNote:        mockMoveNote,
    createFolder:    mockCreateFolder,
    fetchChildrenOf: mockFetchChildrenOf,
  })),
}));

vi.mock('../molecules/NavItem', () => ({
  default: ({ name, children, onClick }) => (
    <div data-testid={`navitem-${name}`} onClick={onClick}>
      <span>{name}</span>
      {children}
    </div>
  ),
}));

vi.mock('../molecules/SearchBar', () => ({
  default: ({ value, onChange }) => (
    <input
      data-testid="search-input"
      placeholder="search"
      value={value}
      onChange={e => onChange(e.target.value)}
    />
  ),
}));

vi.mock('../atoms/Icon', () => ({ default: () => null }));

beforeEach(() => {
  vi.clearAllMocks();
  mockFetchChildrenOf.mockResolvedValue(undefined);
});

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('FolderTree search filter', () => {
  it('shows all entries when query is empty', () => {
    render(<FolderTree />);
    expect(screen.getByTestId('navitem-MatchingNote')).toBeInTheDocument();
    expect(screen.getByTestId('navitem-OtherNote')).toBeInTheDocument();
    expect(screen.getByTestId('navitem-FolderA')).toBeInTheDocument();
  });

  it('hides files that do not match the query', () => {
    render(<FolderTree />);
    fireEvent.change(screen.getByTestId('search-input'), { target: { value: 'matching' } });
    expect(screen.queryByTestId('navitem-OtherNote')).toBeNull();
  });

  it('shows files that match the query', () => {
    render(<FolderTree />);
    fireEvent.change(screen.getByTestId('search-input'), { target: { value: 'matching' } });
    expect(screen.getByTestId('navitem-MatchingNote')).toBeInTheDocument();
  });

  it('shows a folder when a descendant matches', () => {
    render(<FolderTree />);
    fireEvent.change(screen.getByTestId('search-input'), { target: { value: 'matchingchild' } });
    expect(screen.getByTestId('navitem-FolderA')).toBeInTheDocument();
  });

  it('hides a folder with no matching descendants', () => {
    render(<FolderTree />);
    fireEvent.change(screen.getByTestId('search-input'), { target: { value: 'matching' } });
    // EmptyFolder has no children at all — should be hidden
    expect(screen.queryByTestId('navitem-EmptyFolder')).toBeNull();
  });

  it('shows "No results" when nothing matches', () => {
    render(<FolderTree />);
    fireEvent.change(screen.getByTestId('search-input'), { target: { value: 'zzznomatch' } });
    expect(screen.getByText(/No results for/i)).toBeInTheDocument();
  });

  it('hides "No results" when query is empty', () => {
    render(<FolderTree />);
    expect(screen.queryByText(/No results for/i)).toBeNull();
  });

  it('match is case-insensitive', () => {
    render(<FolderTree />);
    fireEvent.change(screen.getByTestId('search-input'), { target: { value: 'MATCHING' } });
    expect(screen.getByTestId('navitem-MatchingNote')).toBeInTheDocument();
    expect(screen.queryByTestId('navitem-OtherNote')).toBeNull();
  });
});
