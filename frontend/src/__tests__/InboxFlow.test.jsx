import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

const h = vi.hoisted(() => ({ fetchInbox: vi.fn(), fileNote: vi.fn(), moveUnit: vi.fn() }));

vi.mock('../pwa/offlineApi', () => ({
  fetchInboxOffline: h.fetchInbox,
  fileInboxOffline: h.fileNote,
  discardInboxOffline: vi.fn(),
  acknowledgeOffline: vi.fn(),
}));
vi.mock('../api/notes', () => ({
  fetchChildren: vi.fn(async () => ({ parentPath: '/v', folderPaths: [] })),
  updateNote: vi.fn(),
}));
vi.mock('../api/inbox', () => ({ splitInboxNote: vi.fn(), moveInboxNote: vi.fn(), createInboxFolder: vi.fn() }));
vi.mock('../pwa/db', () => ({ getMeta: vi.fn(async () => null), setMeta: vi.fn(async () => {}) }));
vi.mock('../utils/dailyDuty', () => ({ markLearnTaskDone: vi.fn() }));
vi.mock('../components/molecules/MarkdownView', () => ({ default: ({ content }) => <pre data-testid="preview">{content}</pre> }));
vi.mock('../components/organisms/SourceSplicePanel', () => ({ default: () => <div data-testid="source-panel" /> }));
vi.mock('../components/templates/LearnLayout', () => ({ default: ({ slotA, slotB }) => <div>{slotA}{slotB}</div> }));

import InboxReview, { stripLeadingTitle } from '../components/organisms/InboxReview';
import useStore from '../store/useStore';

// Note-first: nothing is auto-selected; the queue drawer starts open and picking a row closes it.
const openNote = async (title) => {
  fireEvent.click(await screen.findByText(title));        // folder header (collapsed by default)
  fireEvent.click(screen.getAllByText(title).at(-1));     // the note row inside
  return screen.findByTestId('preview');
};

const note = (path, title, captureId, extra = {}) => ({
  path, title, content: `# ${title}\n\nbody of ${title}`, captureId, captureSeq: 1,
  suggestedFolder: '/v/Dest', inPlace: false, ...extra,
});

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  useStore.setState({ isAuthenticated: true, todayUnits: null, tracks: [],
    fetchTodayPlan: vi.fn(async () => {}), fetchTracks: vi.fn(async () => {}), moveUnit: h.moveUnit });
  h.fileNote.mockResolvedValue();
});

describe('stripLeadingTitle', () => {
  it('drops a leading H1 that repeats the title', () => {
    expect(stripLeadingTitle('# My Note\n\nbody', 'My note')).toBe('body');
  });
  it('keeps frontmatter and drops the H1 after it', () => {
    expect(stripLeadingTitle('---\na: 1\n---\n# My Note\nbody', 'My Note')).toBe('---\na: 1\n---\nbody');
  });
  it('leaves a different heading alone', () => {
    expect(stripLeadingTitle('# Other\nbody', 'My Note')).toBe('# Other\nbody');
  });
});

describe('InboxReview flow', () => {
  it('shows a skeleton, not the empty message, until the first load settles', async () => {
    let resolve;
    h.fetchInbox.mockReturnValue(new Promise(r => { resolve = r; }));
    render(<InboxReview />);
    expect(screen.queryByText(/Inbox is empty/)).toBeNull();
    resolve([]);
    await screen.findByText(/Inbox is empty/);
  });

  it('preview omits the repeated title', async () => {
    h.fetchInbox.mockResolvedValue([note('/a1', 'Alpha', 1)]);
    render(<InboxReview />);
    const pre = await openNote('Alpha');
    expect(pre.textContent).toBe('body of Alpha');
  });

  it('Enter files the note; afterwards it continues into the NEXT group', async () => {
    const a1 = note('/a1', 'Alpha', 1);
    const b1 = note('/b1', 'Beta', 2);
    h.fetchInbox.mockResolvedValueOnce([a1, b1]).mockResolvedValue([b1]);
    render(<InboxReview />);
    await openNote('Alpha');
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(h.fileNote).toHaveBeenCalledWith('/a1', '/v/Dest', a1.content));
    await waitFor(() => expect(screen.getByTestId('preview').textContent).toBe('body of Beta'));
  });

  // 6bbc2c6 — InboxReview had its own local groupKeyOf (captureId || path), a stale
  // duplicate of sourceColor.js's inboxGroupKey (captureId || inboxFolder || path) that
  // drifted out of sync. A note with neither a captureId NOR a shared inboxFolder fell
  // through to its own unique path, so nextInGroup could never find its sibling and
  // auto-advance silently stopped firing for manually-organized mini-folder notes.
  it('discarding an un-captured note advances to its sibling in the same mini-folder, not an unrelated source', async () => {
    const b1 = note('/b1', 'Beta', 9);
    const a1 = note('/a1', 'Alpha', null, { inboxFolder: 'Misc' });
    const a2 = note('/a2', 'Alpha Two', null, { inboxFolder: 'Misc' });
    h.fetchInbox.mockResolvedValueOnce([b1, a1, a2]).mockResolvedValue([b1, a2]);
    render(<InboxReview />);
    await screen.findByText('Misc');
    // Expand the "Misc" mini-folder, then start on Alpha (same group as the sibling we
    // expect to land on next).
    fireEvent.click(screen.getByTitle('Misc'));
    fireEvent.click(screen.getByText('Alpha'));
    await waitFor(() => expect(screen.getByTestId('preview').textContent).toBe('body of Alpha'));

    fireEvent.click(screen.getByRole('button', { name: 'More actions' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Discard' }));

    await waitFor(() => expect(screen.getByTestId('preview').textContent).toBe('body of Alpha Two'));
  });

  it('Enter inside the editor textarea does not file (needs Ctrl/Cmd)', async () => {
    h.fetchInbox.mockResolvedValue([note('/a1', 'Alpha', 1)]);
    render(<InboxReview />);
    await openNote('Alpha');
    fireEvent.click(screen.getByRole('tab', { name: 'Edit' }));
    const ta = document.querySelector('textarea');
    fireEvent.keyDown(ta, { key: 'Enter' });
    expect(h.fileNote).not.toHaveBeenCalled();
    fireEvent.keyDown(ta, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(h.fileNote).toHaveBeenCalled());
  });

  it('text source: note only, no compare toggle, no source panel; Speed read swaps in RSVP with Back', async () => {
    h.fetchInbox.mockResolvedValue([note('/a1', 'Alpha', 1)]);
    render(<InboxReview />);
    await openNote('Alpha');
    expect(screen.queryByRole('button', { name: /compare with source/i })).toBeNull();
    expect(screen.queryByTestId('source-panel')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Speed read' }));
    expect(screen.queryByTestId('preview')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByTestId('preview')).toBeTruthy();
  });

  it('video/pdf/web source: compare toggle (off by default, remembered), no Speed read', async () => {
    const pdf = note('/p1', 'Paper', 3, { source: 'resources/p.pdf', content: '# Paper\n\nbody\n\n## Source\nresources/p.pdf (pages 2-3)\n' });
    h.fetchInbox.mockResolvedValue([pdf]);
    render(<InboxReview />);
    await openNote('Paper');
    expect(screen.queryByRole('button', { name: 'Speed read' })).toBeNull();
    expect(screen.queryByTestId('source-panel')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /compare with source/i }));
    expect(screen.getByTestId('source-panel')).toBeTruthy();
    expect(localStorage.getItem('learn:compareSource')).toBe('1');
  });

  it('splits the queue into Today units and a collapsed Backlog; picking a note closes the drawer', async () => {
    const a1 = note('/a1', 'Alpha', 'cap1', { inboxFolder: 'cap1' });
    const b1 = note('/b1', 'Beta', 'cap2', { inboxFolder: 'cap2' });
    const loose = note('/l1', 'Loose', null);
    h.fetchInbox.mockResolvedValue([a1, b1, loose]);
    useStore.setState({ todayUnits: [{ groupKey: 'cap1', captureId: 'cap1', chapter: null, title: 'Unit One', noteCount: 1, trackId: 7, trackTitle: 'General' }] });
    render(<InboxReview />);
    await screen.findByText('Unit One');
    expect(screen.getByText('General')).toBeTruthy();
    expect(screen.queryByText('Beta')).toBeNull();
    expect(screen.queryByText('Loose')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Backlog \(2\)/ }));
    expect(screen.getByText('Loose')).toBeTruthy();
    fireEvent.click(screen.getByTitle('Unit One'));
    fireEvent.click(screen.getByText('Alpha'));
    await screen.findByTestId('preview');
    expect(screen.queryByRole('complementary', { name: 'Queue' })).toBeNull();
  });

  it('track selector moves the unit (captureId + chapter slug)', async () => {
    const c1 = note('/c1', 'Ch One', 'cap1', { inboxFolder: 'cap1/ch-1' });
    h.fetchInbox.mockResolvedValue([c1]);
    h.moveUnit.mockResolvedValue();
    useStore.setState({
      tracks: [{ id: 7, title: 'General' }, { id: 9, title: 'Rust' }],
      todayUnits: [{ groupKey: 'cap1/ch-1', captureId: 'cap1', chapter: 'ch-1', title: 'Book / ch-1', noteCount: 1, trackId: 7, trackTitle: 'General' }],
    });
    render(<InboxReview />);
    fireEvent.click(await screen.findByTitle('Book / ch-1'));
    fireEvent.click(screen.getByText('Ch One'));
    await screen.findByTestId('preview');
    fireEvent.click(screen.getByRole('button', { name: /Track: General/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Rust' }));
    await waitFor(() => expect(h.moveUnit).toHaveBeenCalledWith('cap1', 'ch-1', 9));
  });

  it('overflow menu closes on Escape', async () => {
    h.fetchInbox.mockResolvedValue([note('/a1', 'Alpha', 1)]);
    render(<InboxReview />);
    await openNote('Alpha');
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }));
    expect(screen.getByRole('menuitem', { name: 'Split' })).toBeTruthy();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('menuitem', { name: 'Split' })).toBeNull();
  });
});
