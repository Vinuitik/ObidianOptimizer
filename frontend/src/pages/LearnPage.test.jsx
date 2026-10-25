import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import LearnPage from './LearnPage';

const state = { todayItems: [{ itemId: 1, notePath: '/v/Notes/x.md' }], isAuthenticated: true, vaultRoot: {}, settings: {}, fetchRootChildren: vi.fn(), fetchNoteNames: vi.fn() };
vi.mock('../store/useStore', () => ({ default: vi.fn(sel => sel(state)) }));
vi.mock('../components/organisms/InboxReview', () => ({ default: () => <div data-testid="inbox" /> }));
vi.mock('../components/organisms/TodayPlan', () => ({ default: () => <div data-testid="today" /> }));
vi.mock('../components/organisms/WeekPlan', () => ({ default: () => <div data-testid="week" /> }));

describe('LearnPage', () => {
  it('Today view shows the Inbox first with the plan below on one screen', () => {
    render(<LearnPage />);
    const today = screen.getByTestId('today');
    const inbox = screen.getByTestId('inbox');
    expect(inbox.compareDocumentPosition(today) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByTestId('week')).toBeNull();
    expect(screen.queryByText('Feed')).toBeNull();
  });

  it('hides the scheduled-items plan when every item is an inbox unit', () => {
    state.todayItems = [{ itemId: 2, notePath: '/v/_inbox/cap/n.md' }];
    render(<LearnPage />);
    state.todayItems = [{ itemId: 1, notePath: '/v/Notes/x.md' }];
    expect(screen.queryByTestId('today')).toBeNull();
    expect(screen.getByTestId('inbox')).toBeTruthy();
  });

  it('Week tab swaps to the week view', () => {
    render(<LearnPage />);
    fireEvent.click(screen.getByRole('tab', { name: 'Week' }));
    expect(screen.getByTestId('week')).toBeTruthy();
    expect(screen.queryByTestId('inbox')).toBeNull();
  });
});
