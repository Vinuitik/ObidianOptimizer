import { describe, it, expect, vi, beforeEach } from 'vitest';

// a549073 — a worker already sitting in `registration.waiting` at COLD START (a previous
// visit finished downloading it and nobody tapped Update) used to just sit there,
// silently running the OLD code until the user happened to notice the update banner. It
// now applies automatically on cold start, since there's nothing in-progress to interrupt.

function mockServiceWorker(registration) {
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: {
      register: vi.fn().mockResolvedValue(registration),
      controller: {},
      addEventListener: vi.fn(),
      getRegistration: vi.fn().mockResolvedValue(registration),
    },
  });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
}

beforeEach(() => {
  vi.resetModules();
  vi.useRealTimers();
});

describe('registerServiceWorker — cold-start waiting worker', () => {
  it('a worker already waiting at cold start is applied immediately (SKIP_WAITING sent)', async () => {
    const waiting = { postMessage: vi.fn() };
    mockServiceWorker({ waiting, installing: null, addEventListener: vi.fn(), update: vi.fn() });

    const { registerServiceWorker } = await import('./registerSW');
    await registerServiceWorker();

    expect(waiting.postMessage).toHaveBeenCalledWith('SKIP_WAITING');
  });

  it('a fresh install (no existing controller) does not auto-apply', async () => {
    const waiting = { postMessage: vi.fn() };
    mockServiceWorker({ waiting, installing: null, addEventListener: vi.fn(), update: vi.fn() });
    Object.defineProperty(navigator.serviceWorker, 'controller', { configurable: true, value: null });

    const { registerServiceWorker } = await import('./registerSW');
    await registerServiceWorker();

    expect(waiting.postMessage).not.toHaveBeenCalled();
  });

  it('no waiting worker at all does not apply anything', async () => {
    mockServiceWorker({ waiting: null, installing: null, addEventListener: vi.fn(), update: vi.fn() });

    const { registerServiceWorker } = await import('./registerSW');
    const result = await registerServiceWorker();

    expect(result).toBeTruthy();
  });
});
