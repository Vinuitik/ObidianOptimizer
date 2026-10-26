import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ImportPanel from './ImportPanel';

vi.mock('../api/importNotes', () => ({ importNotes: vi.fn() }));
import { importNotes } from '../api/importNotes';

beforeEach(() => vi.clearAllMocks());
const importBtn = () => screen.getByRole('button', { name: /^Import/ });

describe('ImportPanel', () => {
  it('disables Import until a file is chosen', () => {
    render(<ImportPanel />);
    expect(importBtn()).toBeDisabled();
  });

  it('uploads and shows the summary with skip reasons', async () => {
    importNotes.mockResolvedValue({
      notes: 12, attachments: 3, queuedForIngest: 1, folders: ['_inbox/Imported/notion-2026-10-26'],
      skipped: [{ path: 'x.zip: a/b.zip', reason: 'nested zip' }],
    });
    render(<ImportPanel />);
    const file = new File(['x'], 'export.zip', { type: 'application/zip' });
    fireEvent.change(screen.getByLabelText('Files to import'), { target: { files: [file] } });
    fireEvent.click(importBtn());
    expect(await screen.findByText(/Imported 12 notes, 3 attachments, 1 sent to the ingest queue, 1 skipped/)).toBeInTheDocument();
    expect(screen.getByText(/nested zip/)).toBeInTheDocument();
    expect(importNotes).toHaveBeenCalledWith([file]);
  });

  it('shows the server error', async () => {
    importNotes.mockRejectedValue(new Error('Zip has more than 20000 entries'));
    render(<ImportPanel />);
    const file = new File(['x'], 'big.zip');
    fireEvent.change(screen.getByLabelText('Files to import'), { target: { files: [file] } });
    fireEvent.click(importBtn());
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('20000 entries'));
  });
});
