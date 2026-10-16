import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import SourceSplicePanel from './SourceSplicePanel';

// b80516d — Instagram/TikTok reels are best-effort yt-dlp downloads; without a local copy,
// the platform blocks third-party iframing (X-Frame-Options/CSP) and the embed used to
// render as a dead blank iframe. These hosts now get an "open original" link instead.

vi.mock('../molecules/NoteRenderer', () => ({ default: () => null }));

describe('SourceSplicePanel — blocked embed hosts', () => {
  it('an Instagram source with no local copy shows an "Open original" link, not an iframe', () => {
    const item = { path: '/a.md', content: '## Source\nhttps://www.instagram.com/reel/abc123\n',
                    source: 'https://www.instagram.com/reel/abc123' };
    render(<SourceSplicePanel item={item} />);

    expect(screen.getByRole('link', { name: /open original/i })).toHaveAttribute(
      'href', 'https://www.instagram.com/reel/abc123',
    );
    expect(document.querySelector('iframe')).toBeNull();
  });

  it('a plain web source with no blocked host still embeds as an iframe', () => {
    const item = { path: '/b.md', content: '## Source\nhttps://example.com/article\n',
                    source: 'https://example.com/article' };
    render(<SourceSplicePanel item={item} />);

    expect(document.querySelector('iframe')).toHaveAttribute('src', 'https://example.com/article');
  });
});
