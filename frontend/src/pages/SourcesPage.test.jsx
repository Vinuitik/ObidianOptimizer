import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SourcesPage from './SourcesPage';

const state = { isAuthenticated: true };
vi.mock('../store/useStore', () => ({ default: vi.fn(sel => sel(state)) }));
vi.mock('../components/organisms/FeedView', () => ({ default: ({ view }) => <div data-testid="feed">{view}</div> }));

describe('SourcesPage', () => {
  it('has one tab row Feed · Subscriptions, no Capture', () => {
    render(<SourcesPage />);
    expect(screen.getAllByRole('tab').map(t => t.textContent)).toEqual(['Feed', 'Subscriptions']);
    expect(screen.getByTestId('feed').textContent).toBe('feed');
    fireEvent.click(screen.getByRole('tab', { name: 'Subscriptions' }));
    expect(screen.getByTestId('feed').textContent).toBe('subscriptions');
  });

  it('gates when signed out', () => {
    state.isAuthenticated = false;
    render(<SourcesPage />);
    expect(screen.getByText('Sign in to use Sources.')).toBeTruthy();
    state.isAuthenticated = true;
  });
});
