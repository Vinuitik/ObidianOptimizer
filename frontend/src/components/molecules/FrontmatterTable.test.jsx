import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import FrontmatterTable from './FrontmatterTable';

describe('FrontmatterTable', () => {
  it('renders nothing for empty frontmatter string', () => {
    const { container } = render(<FrontmatterTable frontmatter="" />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing for frontmatter with no key:value pairs', () => {
    const { container } = render(<FrontmatterTable frontmatter="---\n---\n" />);
    expect(container.firstChild).toBeNull();
  });

  it('renders a row per field', () => {
    const fm = '---\nsr-due: 2025-01-01\nsr-interval: 7\nsr-ease: 250\n---\n\n';
    render(<FrontmatterTable frontmatter={fm} />);
    expect(screen.getByText('sr-due')).toBeInTheDocument();
    expect(screen.getByText('2025-01-01')).toBeInTheDocument();
    expect(screen.getByText('sr-interval')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByText('sr-ease')).toBeInTheDocument();
    expect(screen.getByText('250')).toBeInTheDocument();
  });

  it('renders em-dash for empty value', () => {
    const fm = '---\nsr-due:\n---\n';
    render(<FrontmatterTable frontmatter={fm} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('renders a table element', () => {
    const fm = '---\nkey: val\n---\n';
    render(<FrontmatterTable frontmatter={fm} />);
    expect(screen.getByRole('table')).toBeInTheDocument();
  });

  it('ignores lines without a colon', () => {
    const fm = '---\nnocolon\nkey: val\n---\n';
    render(<FrontmatterTable frontmatter={fm} />);
    const rows = screen.getAllByRole('row');
    expect(rows).toHaveLength(1);
    expect(screen.getByText('key')).toBeInTheDocument();
  });
});
