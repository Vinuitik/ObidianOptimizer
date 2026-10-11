import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import QuickCapture, { noteNameFromText } from './QuickCapture';
import useStore from '../../store/useStore';

vi.mock('../../api/capture', () => ({
  captureResource: vi.fn(),
  captureFile: vi.fn(),
}));
vi.mock('../../api/notes', () => ({
  createNote: vi.fn(),
  updateNote: vi.fn(),
  searchNotes: vi.fn(),
}));

import { captureResource, captureFile } from '../../api/capture';
import { createNote, updateNote } from '../../api/notes';

const showToast = vi.fn();

function setup() {
  useStore.setState({ vaultRoot: '/vault', showToast });
  return render(<MemoryRouter><QuickCapture /></MemoryRouter>);
}

function openAndType(value) {
  fireEvent.click(screen.getByRole('button', { name: /capture/i }));
  fireEvent.change(screen.getByLabelText('Capture'), { target: { value } });
}

beforeEach(() => {
  vi.clearAllMocks();
  captureResource.mockResolvedValue({ ok: true, duplicate: false });
  captureFile.mockResolvedValue({ ok: true, duplicate: false });
  createNote.mockResolvedValue({ path: '/vault/_inbox/Hello.md' });
  updateNote.mockResolvedValue();
});

describe('QuickCapture', () => {
  it('opens with the c shortcut but not while typing', () => {
    setup();
    fireEvent.keyDown(document.body, { key: 'c' });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('ignores c inside an input', () => {
    setup();
    const input = document.createElement('input');
    document.body.appendChild(input);
    fireEvent.keyDown(input, { key: 'c' });
    expect(screen.queryByRole('dialog')).toBeNull();
    input.remove();
  });

  it('URL path: captures without a track and toasts with View in Inbox', async () => {
    setup();
    openAndType('https://example.com/a');
    fireEvent.click(screen.getByRole('button', { name: 'Capture 1 link' }));
    await waitFor(() => expect(showToast).toHaveBeenCalled());
    expect(captureResource).toHaveBeenCalledWith('https://example.com/a');
    expect(showToast.mock.calls[0][0].action.label).toBe('View in Inbox');
    expect(createNote).not.toHaveBeenCalled();
  });

  it('file path: uploads via captureFile', async () => {
    setup();
    fireEvent.click(screen.getByRole('button', { name: /capture/i }));
    const file = new File(['x'], 'doc.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByTestId('quick-capture-file'), { target: { files: [file] } });
    await waitFor(() => expect(captureFile).toHaveBeenCalledWith(file));
    await waitFor(() => expect(showToast).toHaveBeenCalled());
  });

  it('text path: creates a note under _inbox with the text', async () => {
    setup();
    openAndType('Hello world\nsecond line');
    fireEvent.click(screen.getByRole('button', { name: 'Save as note' }));
    await waitFor(() => expect(updateNote).toHaveBeenCalled());
    expect(createNote).toHaveBeenCalledWith('/vault/_inbox', 'Hello world');
    expect(updateNote).toHaveBeenCalledWith('/vault/_inbox/Hello.md', 'Hello world\nsecond line');
    expect(showToast.mock.calls[0][0].action.label).toBe('View in Inbox');
  });

  it('shows an inline error and no success toast when capture fails', async () => {
    captureResource.mockResolvedValue({ ok: false, error: 'bad url' });
    setup();
    openAndType('https://example.com/a');
    fireEvent.click(screen.getByRole('button', { name: 'Capture 1 link' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('bad url');
    expect(showToast).not.toHaveBeenCalled();
  });
});

describe('noteNameFromText', () => {
  it('strips path characters and truncates', () => {
    expect(noteNameFromText('a/b: [c]\nmore')).toBe('a b c');
    expect(noteNameFromText('x'.repeat(100))).toHaveLength(60);
  });
});
