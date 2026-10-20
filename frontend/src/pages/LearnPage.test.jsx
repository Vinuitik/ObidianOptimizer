import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import LearnPage from './LearnPage';

const state = { isAuthenticated: true, vaultRoot: {}, settings: {}, fetchRootChildren: vi.fn(), fetchNoteNames: vi.fn() };
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

  it('Week tab swaps to the week view', () => {
    render(<LearnPage />);
    fireEvent.click(screen.getByRole('tab', { name: 'Week' }));
    expect(screen.getByTestId('week')).toBeTruthy();
    expect(screen.queryByTestId('inbox')).toBeNull();
  });
});
