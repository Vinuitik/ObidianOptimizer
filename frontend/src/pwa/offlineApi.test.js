import { describe, it, expect, vi, beforeEach } from 'vitest';

// IndexedDB-touching modules are mocked (jsdom has no indexedDB); we only exercise the
// captureText transport-decision logic: POST when online, queue when offline / on 401.
vi.mock('./connectivity', () => ({ isOnline: vi.fn() }));
vi.mock('./db', () => ({
  getAllReviewNotes: vi.fn(), getReviewNote: vi.fn(), getAssignmentByNote: vi.fn(),
  getMeta: vi.fn(), setMeta: vi.fn(),
}));
vi.mock('./outbox', () => ({
  enqueueGrade: vi.fn(), enqueueCapture: vi.fn(), enqueueCaptureText: vi.fn(),
  enqueueAssignment: vi.fn(), enqueueFile: vi.fn(), enqueueDiscard: vi.fn(),
  enqueueAcknowledge: vi.fn(), enqueueFlag: vi.fn(), flush: vi.fn(),
}));

import { captureText, flagCardOffline } from './offlineApi';
import { isOnline } from './connectivity';
import { enqueueCaptureText, enqueueFlag } from './outbox';

describe('captureText', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    global.fetch = vi.fn();
  });

  it('online + ok → POSTs {text,title} to /api/capture, does not queue', async () => {
    isOnline.mockReturnValue(true);
    global.fetch.mockResolvedValue({ ok: true });

    const res = await captureText('rough idea', 'My note');

    expect(res).toEqual({ queued: false });
    expect(global.fetch).toHaveBeenCalledWith('/api/capture', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ text: 'rough idea', title: 'My note' }),
    }));
    expect(enqueueCaptureText).not.toHaveBeenCalled();
  });

  it('offline → queues, never hits the network', async () => {
    isOnline.mockReturnValue(false);

    const res = await captureText('later', null);

    expect(res).toEqual({ queued: true });
    expect(global.fetch).not.toHaveBeenCalled();
    expect(enqueueCaptureText).toHaveBeenCalledWith('later', null, {});
  });

  it('online but 401 → queues for replay after login', async () => {
    isOnline.mockReturnValue(true);
    global.fetch.mockResolvedValue({ ok: false, status: 401 });

    const res = await captureText('signed out dump', null);

    expect(res).toEqual({ queued: true, reason: 'auth' });
    expect(enqueueCaptureText).toHaveBeenCalledWith('signed out dump', null, {});
  });

  it('network throw → queues (server unreachable mid-request)', async () => {
    isOnline.mockReturnValue(true);
    global.fetch.mockRejectedValue(new TypeError('Failed to fetch'));

    const res = await captureText('flaky', null);

    expect(res).toEqual({ queued: true });
    expect(enqueueCaptureText).toHaveBeenCalledWith('flaky', null, {});
  });
});

// Exercises the shared withOfflineFallback helper via a function with NO special-case 401
// handling (unlike captureText above) — a 401 here must propagate, not queue.
describe('flagCardOffline', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    global.fetch = vi.fn();
  });

  it('online + ok → POSTs to /cards/:id/flag, does not queue', async () => {
    isOnline.mockReturnValue(true);
    global.fetch.mockResolvedValue({ ok: true });

    const res = await flagCardOffline('card-1', 'bad question');

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/cards/card-1/flag'),
      expect.objectContaining({ method: 'POST' }),
    );
    expect(res).toBeUndefined(); // flagCard() has no return value on success
    expect(enqueueFlag).not.toHaveBeenCalled();
  });

  it('offline → queues, never hits the network', async () => {
    isOnline.mockReturnValue(false);

    const res = await flagCardOffline('card-2', 'reason');

    expect(res).toEqual({ queued: true });
    expect(global.fetch).not.toHaveBeenCalled();
    expect(enqueueFlag).toHaveBeenCalledWith('card-2', 'reason');
  });

  it('online but 401 → propagates (no queued-with-reason special case here)', async () => {
    isOnline.mockReturnValue(true);
    global.fetch.mockResolvedValue({ ok: false, status: 401 });

    await expect(flagCardOffline('card-3', 'reason')).rejects.toThrow();
    expect(enqueueFlag).not.toHaveBeenCalled();
  });

  it('server unreachable (530) → queues', async () => {
    isOnline.mockReturnValue(true);
    global.fetch.mockResolvedValue({ ok: false, status: 530 });

    const res = await flagCardOffline('card-4', 'reason');

    expect(res).toEqual({ queued: true });
    expect(enqueueFlag).toHaveBeenCalledWith('card-4', 'reason');
  });
});
