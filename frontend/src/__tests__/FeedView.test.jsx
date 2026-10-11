import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import FeedView from '../components/organisms/FeedView';
import useStore from '../store/useStore';
import * as feed from '../api/feed';
import { ApiError } from '../api/notes';

vi.mock('../api/feed');

const item = (id, extra = {}) => ({
  id, sourceId: 1, sourceTitle: 'Chan', itemUrl: `https://y/${id}`, title: `Video ${id}`,
  thumbUrl: null, durationS: 65, publishedAt: '2026-10-01T00:00:00Z', status: 'pending', ...extra,
});

// jsdom has no PointerEvent → testing-library falls back to a bare Event, dropping clientX.
function pointer(el, type, clientX) {
  const ev = new Event(type, { bubbles: true });
  Object.defineProperty(ev, 'clientX', { value: clientX });
  act(() => { el.dispatchEvent(ev); });
}
function swipe(dx) {
  const card = screen.getByTestId('feed-card');
  pointer(card, 'pointerdown', 100);
  pointer(card, 'pointermove', 100 + dx);
  pointer(card, 'pointerup', 100 + dx);
}

beforeEach(() => {
  vi.resetAllMocks();
  feed.acceptFeedItem.mockResolvedValue({ captureId: 7 });
  feed.binFeedItem.mockResolvedValue();
  feed.restoreFeedItem.mockResolvedValue();
  feed.fetchFeedItems.mockResolvedValue([]);
  feed.fetchFeedSources.mockResolvedValue([]);
  useStore.setState({
    feedPending: [item(1), item(2)], feedBinned: [], feedSources: [],
    feedLastDecision: null, feedNotice: null,
  });
});

describe('FeedView', () => {
  it('shows the first pending card', () => {
    render(<FeedView />);
    expect(screen.getByText('Video 1')).toBeTruthy();
  });

  it('swipe right accepts and advances immediately', async () => {
    render(<FeedView />);
    swipe(120);
    expect(screen.getByText('Video 2')).toBeTruthy(); // optimistic, no wait on server
    await waitFor(() => expect(feed.acceptFeedItem).toHaveBeenCalledWith(1));
  });

  it('swipe left bins', async () => {
    render(<FeedView />);
    swipe(-120);
    await waitFor(() => expect(feed.binFeedItem).toHaveBeenCalledWith(1));
    expect(screen.getByText('Video 2')).toBeTruthy();
  });

  it('short drag does nothing', () => {
    render(<FeedView />);
    swipe(30);
    expect(feed.acceptFeedItem).not.toHaveBeenCalled();
    expect(feed.binFeedItem).not.toHaveBeenCalled();
    expect(screen.getByText('Video 1')).toBeTruthy();
  });

  it('ArrowRight accepts, ArrowLeft bins', async () => {
    render(<FeedView />);
    fireEvent.keyDown(window, { key: 'ArrowRight' });
    await waitFor(() => expect(feed.acceptFeedItem).toHaveBeenCalledWith(1));
    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    await waitFor(() => expect(feed.binFeedItem).toHaveBeenCalledWith(2));
  });

  it('keys are ignored while typing in an input', () => {
    render(<FeedView />);
    fireEvent.click(screen.getByRole('tab', { name: 'Sources' }));
    fireEvent.keyDown(screen.getByLabelText('Source URL'), { key: 'ArrowRight' });
    expect(feed.acceptFeedItem).not.toHaveBeenCalled();
  });

  it('Undo restores the last decision', async () => {
    render(<FeedView />);
    fireEvent.click(screen.getByRole('button', { name: 'Bin' }));
    await screen.findByText('Binned.');
    fireEvent.click(screen.getByText('Undo'));
    await waitFor(() => expect(feed.restoreFeedItem).toHaveBeenCalledWith(1));
    expect(screen.getByText('Video 1')).toBeTruthy();
  });

  it('Undo of an accept that already left QUEUED (409) shows the Inbox hint', async () => {
    feed.restoreFeedItem.mockRejectedValue(new ApiError(409));
    render(<FeedView />);
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));
    await screen.findByText('Sent to Inbox.');
    fireEvent.click(screen.getByText('Undo'));
    await screen.findByText(/already ingesting — discard it in Inbox/i);
    expect(screen.queryByText('Video 1')).toBeNull();
  });

  it('rolls the card back with a toast when the request fails', async () => {
    feed.acceptFeedItem.mockRejectedValue(new ApiError(500));
    render(<FeedView />);
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));
    await screen.findByText(/Couldn't accept/);
    expect(screen.getByText('Video 1')).toBeTruthy();
  });

  it('shows the empty state', () => {
    useStore.setState({ feedPending: [] });
    render(<FeedView />);
    expect(screen.getByText(/all caught up/i)).toBeTruthy();
  });

  it('Bin tab: Restore and Ingest anyway', async () => {
    feed.fetchFeedItems.mockResolvedValue([item(5, { status: 'binned' }), item(6, { status: 'binned' })]);
    render(<FeedView />);
    fireEvent.click(screen.getByRole('tab', { name: 'Bin' }));
    await screen.findByText('Video 5');
    fireEvent.click(screen.getAllByText('Restore')[0]);
    await waitFor(() => expect(feed.restoreFeedItem).toHaveBeenCalledWith(5));
    fireEvent.click(screen.getByText('Ingest anyway'));
    await waitFor(() => expect(feed.acceptFeedItem).toHaveBeenCalledWith(6));
    expect(screen.getByText('Bin is empty.')).toBeTruthy();
  });

  it('shows "Subscribe to this channel" only for YouTube items, and subscribes on click', async () => {
    feed.addFeedSource.mockResolvedValue({ id: 9, kind: 'youtube', url: 'https://y/1', title: 'Chan' });
    useStore.setState({
      feedPending: [item(1, { itemUrl: 'https://www.youtube.com/watch?v=abc' }), item(2)],
    });
    render(<FeedView />);
    expect(screen.getByText('🔔 Subscribe to this channel')).toBeTruthy();

    fireEvent.click(screen.getByText('🔔 Subscribe to this channel'));
    await waitFor(() => expect(feed.addFeedSource).toHaveBeenCalledWith('https://www.youtube.com/watch?v=abc'));
    await screen.findByText('Subscribed to this channel.');
  });

  it('does not show "Subscribe to this channel" for a non-YouTube item', () => {
    render(<FeedView />);   // item(1) itemUrl is https://y/1
    expect(screen.queryByText('🔔 Subscribe to this channel')).toBeNull();
  });

  it('Sources tab: add by URL posts and lists the source', async () => {
    feed.addFeedSource.mockResolvedValue({ id: 9, kind: 'youtube', url: 'u', title: 'New Chan', autoAccept: null, pendingCount: 3 });
    render(<FeedView />);
    fireEvent.click(screen.getByRole('tab', { name: 'Sources' }));
    fireEvent.change(screen.getByLabelText('Source URL'), { target: { value: 'https://youtube.com/@x' } });
    fireEvent.click(screen.getByText('Add'));
    await waitFor(() => expect(feed.addFeedSource).toHaveBeenCalledWith('https://youtube.com/@x'));
    await screen.findByText('New Chan');
  });
});
