import { render, screen, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import LearnPage from '../pages/LearnPage';
import useStore from '../store/useStore';

vi.mock('../components/organisms/InboxReview', () => ({ default: () => <div>INBOX VIEW</div> }));
vi.mock('../components/organisms/FeedView', () => ({ default: () => <div>FEED VIEW</div> }));

function setup(pending, loaded = true) {
  useStore.setState({
    isAuthenticated: true, vaultRoot: '/v', learnView: null,
    feedPending: pending, feedLoaded: loaded,
    fetchFeed: vi.fn(async () => {}),
  });
}

describe('LearnPage default tab', () => {
  beforeEach(() => vi.clearAllMocks());

  it('opens on Feed when items are pending', async () => {
    setup([{ id: 1 }]);
    render(<LearnPage />);
    expect(await screen.findByText('FEED VIEW')).toBeTruthy();
  });

  it('opens on Inbox when nothing is pending', async () => {
    setup([]);
    render(<LearnPage />);
    expect(await screen.findByText('INBOX VIEW')).toBeTruthy();
  });

  it('waits (skeleton) until the first feed fetch resolves', () => {
    setup([], false);
    render(<LearnPage />);
    expect(screen.queryByText('INBOX VIEW')).toBeNull();
    expect(screen.queryByText('FEED VIEW')).toBeNull();
  });

  it('does not flip to Inbox when the last card is swiped away', async () => {
    setup([{ id: 1 }]);
    render(<LearnPage />);
    await screen.findByText('FEED VIEW');
    act(() => { useStore.setState({ feedPending: [] }); });
    expect(screen.getByText('FEED VIEW')).toBeTruthy();
  });
});
