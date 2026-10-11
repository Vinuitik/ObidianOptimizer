import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import PipelineFailuresPage from './PipelineFailuresPage';
import ErrorState from '../components/molecules/ErrorState';
import * as captureApi from '../api/capture';
import * as pfApi from '../api/pipelineFailures';
import useStore from '../store/useStore';

const CAPTURE = { id: 7, sourceType: 'youtube_video', sourceRef: 'https://y.t/abc', title: 'A talk', lastError: 'yt-dlp rejected', retryCount: 3, createdAt: '2026-10-01T10:00:00Z' };
const OLD = { id: 1, occurredAt: '2020-01-01T00:00:00Z', source: 'capture', stage: 'fetch_page', inputPayload: '{"url":"https://x.y"}', errorMessage: 'boom', resolvedAt: null };
const FRESH = { id: 2, occurredAt: new Date().toISOString(), source: 'ingest', stage: 'embed', inputPayload: '{"title":"t"}', errorMessage: 'bad', resolvedAt: null };

beforeEach(() => {
  useStore.setState({ isAuthenticated: true, showToast: vi.fn(), loadFailuresCount: vi.fn() });
  vi.spyOn(pfApi, 'fetchPipelineFailures').mockResolvedValue([]);
  vi.spyOn(captureApi, 'fetchFailedCaptures').mockResolvedValue([CAPTURE]);
});
afterEach(() => { vi.restoreAllMocks(); useStore.setState({ isAuthenticated: false }); });

describe('PipelineFailuresPage', () => {
  it('Retry calls the capture API once and removes the row', async () => {
    const retry = vi.spyOn(captureApi, 'retryCapture').mockResolvedValue({});
    render(<PipelineFailuresPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(retry).toHaveBeenCalledWith(7));
    await waitFor(() => expect(screen.queryByText('A talk')).not.toBeInTheDocument());
    expect(captureApi.fetchFailedCaptures).toHaveBeenCalledTimes(1);
  });

  it('Dismiss calls dismissCapture', async () => {
    const dismiss = vi.spyOn(captureApi, 'dismissCapture').mockResolvedValue({});
    render(<PipelineFailuresPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }));
    await waitFor(() => expect(dismiss).toHaveBeenCalledWith(7));
  });

  it('shows ErrorState + Retry (not "clean") when the list fails, and recovers', async () => {
    const f = vi.spyOn(pfApi, 'fetchPipelineFailures');
    f.mockRejectedValueOnce(new Error('502')).mockResolvedValue([]);
    render(<PipelineFailuresPage />);
    expect(await screen.findByText(/Couldn.t load pipeline failures/)).toBeInTheDocument();
    expect(screen.queryByText('502')).not.toBeInTheDocument();
    expect(screen.queryByText(/No open failures/)).not.toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: 'Retry' }).at(-1));
    expect(await screen.findByText('No open failures')).toBeInTheDocument();
  });

  it('resolves only failures older than 7 days', async () => {
    pfApi.fetchPipelineFailures.mockResolvedValue([OLD, FRESH]);
    const resolve = vi.spyOn(pfApi, 'resolvePipelineFailure').mockResolvedValue({});
    render(<PipelineFailuresPage />);
    fireEvent.click(await screen.findByRole('button', { name: /Resolve all older than 7 days \(1\)/ }));
    await waitFor(() => expect(resolve).toHaveBeenCalledTimes(1));
    expect(resolve).toHaveBeenCalledWith(1);
  });
});

describe('ErrorState', () => {
  it('renders message and fires onRetry', () => {
    const onRetry = vi.fn();
    render(<ErrorState message="Couldn’t load x" onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t load x');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalled();
  });
});
