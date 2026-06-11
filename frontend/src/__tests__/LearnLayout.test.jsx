import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import LearnLayout from '../components/templates/LearnLayout';

describe('LearnLayout — swap', () => {
  it('renders slotA in first pane and slotB in second by default', () => {
    render(
      <LearnLayout
        orientation="vertical"
        slotA={<div data-testid="resource">Resource</div>}
        slotB={<div data-testid="note">Note</div>}
      />
    );
    const first  = screen.getByTestId('learn-pane-first');
    const second = screen.getByTestId('learn-pane-second');
    expect(first.querySelector('[data-testid="resource"]')).toBeTruthy();
    expect(second.querySelector('[data-testid="note"]')).toBeTruthy();
  });

  it('clicking swap moves slotB into first pane and slotA into second', () => {
    render(
      <LearnLayout
        orientation="vertical"
        slotA={<div data-testid="resource">Resource</div>}
        slotB={<div data-testid="note">Note</div>}
      />
    );
    fireEvent.click(screen.getByTestId('learn-swap'));
    const first  = screen.getByTestId('learn-pane-first');
    const second = screen.getByTestId('learn-pane-second');
    expect(first.querySelector('[data-testid="note"]')).toBeTruthy();
    expect(second.querySelector('[data-testid="resource"]')).toBeTruthy();
  });

  it('clicking swap twice returns to original layout', () => {
    render(
      <LearnLayout
        orientation="vertical"
        slotA={<div data-testid="resource">Resource</div>}
        slotB={<div data-testid="note">Note</div>}
      />
    );
    fireEvent.click(screen.getByTestId('learn-swap'));
    fireEvent.click(screen.getByTestId('learn-swap'));
    const first = screen.getByTestId('learn-pane-first');
    expect(first.querySelector('[data-testid="resource"]')).toBeTruthy();
  });

  it('applies vertical class for vertical orientation', () => {
    render(
      <LearnLayout orientation="vertical" slotA={<div />} slotB={<div />} />
    );
    expect(screen.getByTestId('learn-layout').className).toMatch(/vertical/);
  });

  it('applies horizontal class for horizontal orientation', () => {
    render(
      <LearnLayout orientation="horizontal" slotA={<div />} slotB={<div />} />
    );
    expect(screen.getByTestId('learn-layout').className).toMatch(/horizontal/);
  });
});
