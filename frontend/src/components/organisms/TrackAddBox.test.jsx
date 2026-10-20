import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import TrackAddBox, { classifyInput, itemKind } from './TrackAddBox';

const mockAdd = vi.fn();
const mockFetch = vi.fn();
const mockToast = vi.fn();

vi.mock('../../store/useStore', () => ({
  default: vi.fn(selector => selector({
    trackItems: { 7: [] },
    fetchTrackItems: mockFetch,
    addTrackItem: mockAdd,
    showToast: mockToast,
  })),
}));

const mockCaptureResource = vi.fn();
const mockCaptureFile = vi.fn();
vi.mock('../../api/capture', () => ({
  captureResource: (...a) => mockCaptureResource(...a),
  captureFile: (...a) => mockCaptureFile(...a),
}));

const mockSearch = vi.fn();
vi.mock('../../api/notes', () => ({ searchNotes: (...a) => mockSearch(...a) }));

beforeEach(() => {
  vi.clearAllMocks();
  mockFetch.mockResolvedValue([]);
  mockAdd.mockResolvedValue({ id: 1 });
  mockSearch.mockResolvedValue([]);
});

describe('classifyInput', () => {
  it('empty', () => {
    expect(classifyInput('  \n ').kind).toBe('empty');
  });
  it('single url', () => {
    expect(classifyInput('https://a.com/x')).toEqual({ kind: 'urls', urls: ['https://a.com/x'] });
  });
  it('multi url, deduped', () => {
    expect(classifyInput('https://a.com\nhttps://b.com\nhttps://a.com').urls).toEqual(['https://a.com', 'https://b.com']);
  });
  it('plain text', () => {
    expect(classifyInput('read chapter 3')).toEqual({ kind: 'text', text: 'read chapter 3' });
  });
  it('mixed url + text is text', () => {
    expect(classifyInput('https://a.com\nnotes').kind).toBe('text');
  });
});

describe('itemKind', () => {
  it('note vs plain', () => {
    expect(itemKind({ notePath: 'a/b.md' })).toBe('note');
    expect(itemKind({ notePath: null })).toBe('plain');
  });
});

describe('TrackAddBox (note picker)', () => {
  it('picking a note match adds an item with notePath', async () => {
    mockSearch.mockResolvedValue([{ path: 'Notes/Graphs.md', title: 'Graphs' }]);
    render(<TrackAddBox trackId={7} />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'graph' } });
    fireEvent.click(await screen.findByText('Graphs'));
    await waitFor(() => expect(mockAdd).toHaveBeenCalledWith(7, 'Graphs', 'Notes/Graphs.md'));
  });

  it('no plain-title add: without a match nothing can be added', async () => {
    render(<TrackAddBox trackId={7} />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'do thing' } });
    expect(await screen.findByText('No matching notes')).toBeTruthy();
    expect(screen.queryByText(/Add as plain item/)).toBeNull();
    expect(screen.queryByRole('button', { name: /Capture|Attach/ })).toBeNull();
    expect(mockAdd).not.toHaveBeenCalled();
  });
});
