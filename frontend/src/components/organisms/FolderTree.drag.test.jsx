import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import FolderTree from './FolderTree';

// ── Store mock ───────────────────────────────────────────────────────────────

const mockMoveNote        = vi.fn();
const mockFetchChildrenOf = vi.fn();
const mockOpenTab         = vi.fn();
const mockStartNewNote    = vi.fn();
const mockDeleteNote      = vi.fn();

const mockState = {
  tree: {
    type: 'folder',
    fullPath: '/vault',
    loaded: true,
    children: {
      FolderA: {
        type: 'folder',
        fullPath: '/vault/FolderA',
        loaded: true,
        children: {},
      },
      'Note.md': {
        type: 'file',
        fullPath: '/vault/Note.md',
      },
    },
  },
  vaultRoot: '/vault',
  currentNotePath: null,
  openTab:         mockOpenTab,
  startNewNote:    mockStartNewNote,
  deleteNote:      mockDeleteNote,
  moveNote:        mockMoveNote,
  fetchChildrenOf: mockFetchChildrenOf,
};

vi.mock('../../store/useStore', () => ({
  default: vi.fn((selector) => selector(mockState)),
}));

// Mock NavItem so the test isn't sensitive to Icon/SVG imports
vi.mock('../molecules/NavItem', () => ({
  default: ({ name, isDragOver, children, onClick }) => (
    <div
      data-testid={`navitem-${name}`}
      data-drag-over={String(isDragOver)}
      onClick={onClick}
    >
      <span>{name}</span>
      {children}
    </div>
  ),
}));

vi.mock('../molecules/SearchBar', () => ({
  default: ({ value, onChange }) => (
    <input placeholder="search" value={value} onChange={e => onChange(e.target.value)} />
  ),
}));

// ── Drag event helpers ───────────────────────────────────────────────────────

const DRAG_TYPE = 'application/obsidian-note';

function makeDragEvent(type, dtOverrides = {}) {
  const dt = {
    setData: vi.fn(),
    getData: vi.fn(() => ''),
    types: [],
    effectAllowed: 'none',
    dropEffect: 'none',
    ...dtOverrides,
  };
  const e = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperty(e, 'dataTransfer', { value: dt });
  return { e, dt };
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('FolderTree drag-and-drop', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockFetchChildrenOf.mockResolvedValue(undefined);
    mockMoveNote.mockResolvedValue(undefined);
  });

  it('file nodes have the draggable attribute', () => {
    const { container } = render(<FolderTree />);
    expect(container.querySelector('[draggable="true"]')).not.toBeNull();
  });

  it('dragStart on a file sets the correct path and effectAllowed in dataTransfer', () => {
    const { getByText } = render(<FolderTree />);
    const noteText = getByText('Note');
    const draggable = noteText.closest('[draggable]');

    const { e, dt } = makeDragEvent('dragstart');
    draggable.dispatchEvent(e);

    expect(dt.setData).toHaveBeenCalledWith(DRAG_TYPE, '/vault/Note.md');
    expect(dt.effectAllowed).toBe('move');
  });

  it('dragOver on a folder with the correct MIME type calls preventDefault', () => {
    const { getByTestId } = render(<FolderTree />);
    const folderWrapper = getByTestId('navitem-FolderA').closest('div[ondragover], div');

    const { e } = makeDragEvent('dragover', { types: [DRAG_TYPE] });
    const notCancelled = folderWrapper.dispatchEvent(e);

    // e.preventDefault() causes dispatchEvent to return false
    expect(notCancelled).toBe(false);
  });

  it('dragOver on a folder with a wrong MIME type does NOT call preventDefault', () => {
    const { getByTestId } = render(<FolderTree />);
    const folderWrapper = getByTestId('navitem-FolderA').closest('div[ondragover], div');

    const { e } = makeDragEvent('dragover', { types: ['text/plain'] });
    const notCancelled = folderWrapper.dispatchEvent(e);

    expect(notCancelled).toBe(true); // not prevented
  });

  it('drop on a folder calls moveNote with source path and target folder path', async () => {
    const { getByTestId } = render(<FolderTree />);
    const folderWrapper = getByTestId('navitem-FolderA').closest('div[ondrop], div');

    const { e } = makeDragEvent('drop', {
      types: [DRAG_TYPE],
      getData: vi.fn(() => '/vault/Note.md'),
    });
    folderWrapper.dispatchEvent(e);

    await vi.waitFor(() => {
      expect(mockMoveNote).toHaveBeenCalledWith('/vault/Note.md', '/vault/FolderA');
    });
  });

  it('drop with an empty source path does NOT call moveNote', async () => {
    const { getByTestId } = render(<FolderTree />);
    const folderWrapper = getByTestId('navitem-FolderA').closest('div[ondrop], div');

    const { e } = makeDragEvent('drop', {
      types: [DRAG_TYPE],
      getData: vi.fn(() => ''),
    });
    folderWrapper.dispatchEvent(e);

    await new Promise(r => setTimeout(r, 10));
    expect(mockMoveNote).not.toHaveBeenCalled();
  });

  it('isDragOver is reflected on the NavItem when dragging over a folder', async () => {
    const { getByTestId } = render(<FolderTree />);
    const navItem = getByTestId('navitem-FolderA');
    const folderWrapper = navItem.closest('div[ondragover], div');

    expect(navItem.getAttribute('data-drag-over')).toBe('false');

    const { e } = makeDragEvent('dragover', { types: [DRAG_TYPE] });
    await act(() => { folderWrapper.dispatchEvent(e); });

    expect(getByTestId('navitem-FolderA').getAttribute('data-drag-over')).toBe('true');
  });
});
