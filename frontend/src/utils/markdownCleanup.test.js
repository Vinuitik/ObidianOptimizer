import { describe, it, expect } from 'vitest';
import { cleanMilkdownOutput } from './markdownCleanup';

describe('cleanMilkdownOutput', () => {
  it('strips standalone <br /> lines', () => {
    const input = 'Para 1\n\n<br />\n\nPara 2';
    expect(cleanMilkdownOutput(input)).toBe('Para 1\n\n\n\nPara 2');
  });

  it('strips <br> and <br/> variants too', () => {
    expect(cleanMilkdownOutput('<br>')).toBe('');
    expect(cleanMilkdownOutput('<br/>')).toBe('');
    expect(cleanMilkdownOutput('<br />')).toBe('');
  });

  it('does NOT strip <br> that is inline within a line', () => {
    const input = 'text <br /> more text';
    expect(cleanMilkdownOutput(input)).toBe('text <br /> more text');
  });

  it('un-escapes \\#hashtag at line start', () => {
    expect(cleanMilkdownOutput('\\#mytag')).toBe('#mytag');
  });

  it('does NOT un-escape \\# followed by space (escaped heading)', () => {
    expect(cleanMilkdownOutput('\\# Heading')).toBe('\\# Heading');
  });

  it('un-escapes \\#tag in the middle of a document', () => {
    const input = 'Some text\n\n\\#topic\n\nMore';
    expect(cleanMilkdownOutput(input)).toBe('Some text\n\n#topic\n\nMore');
  });

  it('leaves clean markdown unchanged', () => {
    const input = '# Heading\n\nParagraph with [[wiki link]] and ![[image.png]]\n\n#tag here';
    expect(cleanMilkdownOutput(input)).toBe(input);
  });

  it('handles multiple <br /> lines', () => {
    const input = 'A\n\n<br />\n\n<br />\n\nB';
    expect(cleanMilkdownOutput(input)).toBe('A\n\n\n\n\n\nB');
  });
});
