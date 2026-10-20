import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SourcesPage from './SourcesPage';

const state = { isAuthenticated: true };
vi.mock('../store/useStore', () => ({ default: vi.fn(sel => sel(state)) }));
vi.mock('../components/organisms/FeedView', () => ({ default: () => <div data-testid="feed" /> }));
vi.mock('../pwa/CapturePage', () => ({ default: () => <div data-testid="capture" /> }));

describe('SourcesPage', () => {
  it('opens on the Feed and switches to Capture', () => {
    render(<SourcesPage />);
    expect(screen.getByTestId('feed')).toBeTruthy();
    expect(screen.queryByTestId('capture')).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: 'Capture' }));
    expect(screen.getByTestId('capture')).toBeTruthy();
    expect(screen.queryByTestId('feed')).toBeNull();
  });

  it('gates when signed out', () => {
    state.isAuthenticated = false;
    render(<SourcesPage />);
    expect(screen.getByText('Sign in to use Sources.')).toBeTruthy();
    state.isAuthenticated = true;
  });
});
