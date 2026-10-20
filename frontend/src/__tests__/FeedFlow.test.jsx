import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import FeedView from '../components/organisms/FeedView';
import Toast from '../components/atoms/Toast';
import useStore from '../store/useStore';
import * as feed from '../api/feed';

vi.mock('../api/feed');

const item = (id) => ({
  id, sourceId: 1, sourceTitle: 'Chan', itemUrl: `https://y/${id}`, title: `Video ${id}`,
  thumbUrl: null, durationS: 65, publishedAt: '2026-10-01T00:00:00Z', status: 'pending',
});
const mount = () => render(<><FeedView /><Toast /></>);
const blog = { id: 3, kind: 'rss', url: 'u', title: 'Blog', autoAccept: null, pendingCount: 0 };

beforeEach(() => {
  vi.resetAllMocks();
  feed.acceptFeedItem.mockResolvedValue({ captureId: 7 });
  feed.fetchFeedItems.mockResolvedValue([]);
  feed.fetchFeedSources.mockResolvedValue([blog]);
  feed.deleteFeedSource.mockResolvedValue();
  useStore.setState({
    feedPending: [item(1), item(2)], feedBinned: [], feedSources: [],
    feedLastDecision: null, feedLoaded: true, toast: null, learnView: null,
  });
});
afterEach(() => vi.useRealTimers());

describe('Feed flow (W2)', () => {
  it('Subscribe: Preview resolves WITHOUT persisting; Subscribe confirms then switches to Swipe', async () => {
    feed.resolveFeedSource.mockResolvedValue({ kind: 'youtube_channel', feedUrl: 'f', title: 'New Chan', preview: [{ title: 'Latest ep' }] });
    feed.fetchFeedItems.mockResolvedValue([item(1)]);
    feed.addFeedSource.mockResolvedValue({ id: 9, kind: 'youtube_channel', url: 'u', title: 'New Chan', autoAccept: null, pendingCount: 3 });
    mount();
    fireEvent.click(screen.getByRole('tab', { name: 'Subscriptions' }));
    fireEvent.change(screen.getByLabelText('Source URL'), { target: { value: 'https://youtube.com/@x' } });
    fireEvent.click(screen.getByText('Preview'));
    await screen.findByText('Latest ep');
    expect(screen.getByText('YouTube channel')).toBeTruthy();   // human label, not "youtube_channel"
    expect(feed.addFeedSource).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Subscribe'));
    await waitFor(() => expect(feed.addFeedSource).toHaveBeenCalledWith('https://youtube.com/@x'));
    await screen.findByTestId('feed-card');   // switched to Swipe
  }, 20000);

  it('Accept toast says "Sent to Inbox" with a View action that opens Learn Inbox', async () => {
    mount();
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));
    await screen.findByText('Sent to Inbox');
    fireEvent.click(screen.getByText('View'));
    expect(useStore.getState().learnView).toBe('inbox');
  });

  it('shows a skeleton, not "all caught up", before the first fetch resolves', () => {
    useStore.setState({ feedPending: [], feedLoaded: false });
    mount();
    expect(screen.queryByText(/all caught up/i)).toBeNull();
    expect(screen.getByRole('status', { name: 'Loading' })).toBeTruthy();
  });

  it('empty state after load offers Open Inbox', () => {
    useStore.setState({ feedPending: [] });
    mount();
    fireEvent.click(screen.getByText('Open Inbox'));
    expect(useStore.getState().learnView).toBe('inbox');
  });

  it('delete source is optimistic; Undo cancels the DELETE', async () => {
    mount();
    fireEvent.click(screen.getByRole('tab', { name: 'Subscriptions' }));
    await screen.findByText('Blog');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fireEvent.click(screen.getByLabelText('Delete Blog'));
    expect(screen.queryByLabelText('Delete Blog')).toBeNull();
    fireEvent.click(screen.getByText('Undo'));
    await act(async () => { vi.advanceTimersByTime(8000); });
    expect(feed.deleteFeedSource).not.toHaveBeenCalled();
    expect(screen.getByText('Blog')).toBeTruthy();
  });

  it('delete source commits after the undo window', async () => {
    mount();
    fireEvent.click(screen.getByRole('tab', { name: 'Subscriptions' }));
    await screen.findByText('Blog');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fireEvent.click(screen.getByLabelText('Delete Blog'));
    await act(async () => { vi.advanceTimersByTime(8000); });
    expect(feed.deleteFeedSource).toHaveBeenCalledWith(3);
  });
});
