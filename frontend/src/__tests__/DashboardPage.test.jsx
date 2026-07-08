import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import DashboardPage from '../pages/DashboardPage';
import * as statsApi from '../api/stats';
import useStore from '../store/useStore';

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div>{children}</div>,
  PieChart: ({ children }) => <svg>{children}</svg>,
  Pie: ({ children }) => <g>{children}</g>,
  Cell: () => <circle />,
}));

const STATS = {
  embedding: { notesTotal: 100, notesEmbedded: 80, chunksTotal: 540 },
  images: { pending: 30, done: 60, skipped: 10 },
  flashcards: { eligibleNotes: 40, notesWithCards: 10, activeCards: 55, archivedCards: 5 },
  resources: { implemented: false },
  wrapper: {
    up: true,
    providers: {
      gemini: { configured: true, in_flight: 1, cooldown_s: 0, ok: 12, failed: 1 },
      groq: { configured: false, in_flight: 0, cooldown_s: 0, ok: 0, failed: 0 },
    },
  },
};

// the page auth-gates before polling — sign the store in for every test
beforeEach(() => useStore.setState({ isAuthenticated: true }));

afterEach(() => {
  vi.restoreAllMocks();
  useStore.setState({ isAuthenticated: false });
});

describe('DashboardPage', () => {
  it('renders chart sections from polled stats', async () => {
    vi.spyOn(statsApi, 'fetchStats').mockResolvedValue(STATS);
    render(<DashboardPage />);

    await waitFor(() => expect(screen.getByText('Note Embedding')).toBeInTheDocument());
    expect(screen.getByText('Image Processing')).toBeInTheDocument();
    expect(screen.getByText('Flashcard Coverage')).toBeInTheDocument();
    expect(screen.getByText('LLM Providers')).toBeInTheDocument();

    // embedding: 80/100 → 80%
    expect(screen.getByText('80%')).toBeInTheDocument();
    // flashcards: 10 / 40 eligible
    expect(screen.getByText('/ 40 eligible notes')).toBeInTheDocument();
    // provider rows
    expect(screen.getByText('gemini')).toBeInTheDocument();
    expect(screen.getByText('working')).toBeInTheDocument();
    expect(screen.getByText('no key')).toBeInTheDocument();
  });

  it('shows wrapper-offline warning', async () => {
    vi.spyOn(statsApi, 'fetchStats').mockResolvedValue({
      ...STATS,
      wrapper: { up: false, providers: null },
    });
    render(<DashboardPage />);
    await waitFor(() =>
      expect(screen.getByText(/Host wrapper offline/)).toBeInTheDocument());
  });

  it('shows error state when fetch fails', async () => {
    vi.spyOn(statsApi, 'fetchStats').mockRejectedValue(new Error('HTTP 500'));
    render(<DashboardPage />);
    await waitFor(() =>
      expect(screen.getByText(/Failed to load stats/)).toBeInTheDocument());
  });
});
