import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import TabBar from './TabBar';

// ── Store mock ────────────────────────────────────────────────────────────────

const mockSwitchTab = vi.fn();
const mockCloseTab  = vi.fn();

let mockTabs = [];
let mockActiveTabIndex = -1;

vi.mock('../../store/useStore', () => ({
  default: vi.fn((selector) => selector({
    tabs:           mockTabs,
    activeTabIndex: mockActiveTabIndex,
    switchTab:      mockSwitchTab,
    closeTab:       mockCloseTab,
  })),
}));

beforeEach(() => {
  vi.clearAllMocks();
  mockTabs = [];
  mockActiveTabIndex = -1;
});

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('TabBar', () => {
  it('renders nothing when tabs array is empty', () => {
    const { container } = render(<TabBar />);
    expect(container.firstChild).toBeNull();
  });

  it('renders one tab button per tab (close buttons are separate role=button spans)', () => {
    mockTabs = [
      { path: '/v/A.md', pendingTitle: 'A', hunks: [] },
      { path: '/v/B.md', pendingTitle: 'B', hunks: [] },
    ];
    render(<TabBar />);
    // Each tab: 1 <button> (tab) + 1 <span role="button"> (close) = 4 total
    const allButtons = screen.getAllByRole('button');
    const tabButtons = allButtons.filter(b => b.getAttribute('aria-label') !== 'Close tab');
    expect(tabButtons).toHaveLength(2);
  });

  it('renders tab title from pendingTitle', () => {
    mockTabs = [{ path: '/v/MyNote.md', pendingTitle: 'MyNote', hunks: [] }];
    render(<TabBar />);
    expect(screen.getByText('MyNote')).toBeInTheDocument();
  });

  it('falls back to basename when pendingTitle is empty', () => {
    mockTabs = [{ path: '/v/FallbackNote.md', pendingTitle: '', hunks: [] }];
    render(<TabBar />);
    expect(screen.getByText('FallbackNote')).toBeInTheDocument();
  });

  it('shows dirty indicator (•) when tab has hunks', () => {
    mockTabs = [{ path: '/v/A.md', pendingTitle: 'A', hunks: [{ startLine: 0, deleteCount: 1, insertLines: [] }] }];
    render(<TabBar />);
    expect(screen.getByTitle('Unsaved changes')).toBeInTheDocument();
  });

  it('does not show dirty indicator when hunks is empty', () => {
    mockTabs = [{ path: '/v/A.md', pendingTitle: 'A', hunks: [] }];
    render(<TabBar />);
    expect(screen.queryByTitle('Unsaved changes')).toBeNull();
  });

  it('clicking a tab calls switchTab with its index', () => {
    mockTabs = [
      { path: '/v/A.md', pendingTitle: 'A', hunks: [] },
      { path: '/v/B.md', pendingTitle: 'B', hunks: [] },
    ];
    render(<TabBar />);
    const tabButtons = screen.getAllByRole('button')
      .filter(b => b.getAttribute('aria-label') !== 'Close tab');
    fireEvent.click(tabButtons[1]); // second tab button
    expect(mockSwitchTab).toHaveBeenCalledWith(1);
  });

  it('clicking close button calls closeTab and does not bubble to switchTab', () => {
    mockTabs = [{ path: '/v/A.md', pendingTitle: 'A', hunks: [] }];
    render(<TabBar />);
    const closeBtn = screen.getByRole('button', { name: 'Close tab' });
    fireEvent.click(closeBtn);
    expect(mockCloseTab).toHaveBeenCalledWith(0);
    expect(mockSwitchTab).not.toHaveBeenCalled();
  });

  it('active tab has the active CSS class applied', () => {
    mockTabs = [
      { path: '/v/A.md', pendingTitle: 'A', hunks: [] },
      { path: '/v/B.md', pendingTitle: 'B', hunks: [] },
    ];
    mockActiveTabIndex = 1;
    const { container } = render(<TabBar />);
    const buttons = container.querySelectorAll('button');
    // The active button should have an extra class (styles.active)
    // In jsdom CSS modules return undefined; we can't check the class name value,
    // but we can check that exactly one button has a className different from the others.
    expect(buttons[0].className).not.toBe(buttons[1].className);
  });
});
