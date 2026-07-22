import { describe, it, expect } from 'vitest';
import { groupBySource, captureLabel, buildInboxTree, folderAllItems } from './sourceColor.js';

// ── captureLabel ──────────────────────────────────────────────────────────────

describe('captureLabel', () => {
  it('shows a 1-based "#N" for an original note (minor 0 / absent)', () => {
    expect(captureLabel({ captureSeq: 0 })).toBe('#1');
    expect(captureLabel({ captureSeq: 10, captureSeqMinor: 0 })).toBe('#11');
  });

  it('shows "#N-M" for a manually-split sibling', () => {
    expect(captureLabel({ captureSeq: 10, captureSeqMinor: 1 })).toBe('#11-1');
    expect(captureLabel({ captureSeq: 10, captureSeqMinor: 2 })).toBe('#11-2');
  });

  it('returns null when there is no capture order', () => {
    expect(captureLabel({})).toBeNull();
    expect(captureLabel({ captureSeq: null })).toBeNull();
  });
});

// ── groupBySource: splits slot in right after their original ────────────────────

describe('groupBySource sub-order', () => {
  it('orders a split sibling (#11-1) directly after its original (#11), before #12', () => {
    // Deliberately shuffled input; same capture, seq 10 has an original + one split.
    const items = [
      { path: 'c.md', captureId: 'cap', captureSeq: 11 },                       // #12
      { path: 'b.md', captureId: 'cap', captureSeq: 10, captureSeqMinor: 1 },   // #11-1
      { path: 'a.md', captureId: 'cap', captureSeq: 10 },                       // #11
    ];
    const ordered = groupBySource(items).map(i => i.path);
    expect(ordered).toEqual(['a.md', 'b.md', 'c.md']);
  });

  it('orders multiple splits by minor (#11, #11-1, #11-2)', () => {
    const items = [
      { path: 'x2.md', captureId: 'cap', captureSeq: 10, captureSeqMinor: 2 },
      { path: 'x0.md', captureId: 'cap', captureSeq: 10 },
      { path: 'x1.md', captureId: 'cap', captureSeq: 10, captureSeqMinor: 1 },
    ];
    expect(groupBySource(items).map(i => i.path)).toEqual(['x0.md', 'x1.md', 'x2.md']);
  });
});

// ── buildInboxTree: source folders + PDF chapter subfolders ─────────────────────

describe('buildInboxTree', () => {
  it('groups standalone notes sharing a captureId into one folder node', () => {
    const items = [
      { path: 'a.md', captureId: 'cap1', sourceTitle: 'My PDF', groupSuggestedFolder: '/vault/AI' },
      { path: 'b.md', captureId: 'cap1', sourceTitle: 'My PDF', groupSuggestedFolder: '/vault/AI' },
    ];
    const tree = buildInboxTree(items);
    expect(tree).toHaveLength(1);
    expect(tree[0]).toMatchObject({ type: 'folder', key: 'cap1', title: 'My PDF', suggestedFolder: '/vault/AI' });
    expect(tree[0].items.map(i => i.path)).toEqual(['a.md', 'b.md']);
    expect(tree[0].chapters).toEqual([]);
  });

  it('nests notes with a chapter into chapter sub-nodes, leaves chapterless notes at the folder level', () => {
    const items = [
      { path: 'intro.md', captureId: 'cap1', sourceTitle: 'Book', chapter: null },
      { path: 'a.md', captureId: 'cap1', sourceTitle: 'Book', chapter: 'Ch 1', chapterSuggestedFolder: '/vault/X' },
      { path: 'b.md', captureId: 'cap1', sourceTitle: 'Book', chapter: 'Ch 1', chapterSuggestedFolder: '/vault/X' },
      { path: 'c.md', captureId: 'cap1', sourceTitle: 'Book', chapter: 'Ch 2' },
    ];
    const tree = buildInboxTree(items);
    expect(tree).toHaveLength(1);
    const folder = tree[0];
    expect(folder.items.map(i => i.path)).toEqual(['intro.md']);
    expect(folder.chapters).toHaveLength(2);
    expect(folder.chapters[0]).toMatchObject({ label: 'Ch 1', suggestedFolder: '/vault/X' });
    expect(folder.chapters[0].items.map(i => i.path)).toEqual(['a.md', 'b.md']);
    expect(folder.chapters[1].items.map(i => i.path)).toEqual(['c.md']);
  });

  it('passes in-place notes and captureId-less notes through as plain leaves', () => {
    const items = [
      { path: 'live.md', inPlace: true, captureId: 'cap9' },
      { path: 'legacy.md', captureId: null },
    ];
    const tree = buildInboxTree(items);
    expect(tree).toEqual([
      { type: 'leaf', item: items[0] },
      { type: 'leaf', item: items[1] },
    ]);
  });

  it('keeps distinct sources as separate top-level folders', () => {
    const items = [
      { path: 'a.md', captureId: 'cap1' },
      { path: 'b.md', captureId: 'cap2' },
    ];
    const tree = buildInboxTree(items);
    expect(tree.map(n => n.key)).toEqual(['cap1', 'cap2']);
  });
});

describe('folderAllItems', () => {
  it('flattens a folder\'s direct items and every chapter\'s items into one list', () => {
    const folder = {
      items: [{ path: 'intro.md' }],
      chapters: [
        { items: [{ path: 'a.md' }, { path: 'b.md' }] },
        { items: [{ path: 'c.md' }] },
      ],
    };
    expect(folderAllItems(folder).map(i => i.path)).toEqual(['intro.md', 'a.md', 'b.md', 'c.md']);
  });
});
