import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import Button from '../components/atoms/Button';
import IconButton from '../components/atoms/IconButton';
import Input from '../components/atoms/Input';
import Badge from '../components/atoms/Badge';
import Skeleton from '../components/atoms/Skeleton';
import Toast from '../components/atoms/Toast';
import Field from '../components/molecules/Field';
import Card from '../components/molecules/Card';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import EmptyState from '../components/molecules/EmptyState';
import ErrorState from '../components/molecules/ErrorState';
import Disclosure from '../components/molecules/Disclosure';
import PageTransition from '../components/motion/PageTransition';
import useStore from '../store/useStore';

describe('atoms', () => {
  it('Button keeps legacy variant="default" and onClick', () => {
    const onClick = vi.fn();
    render(<Button variant="default" onClick={onClick}>Go</Button>);
    fireEvent.click(screen.getByRole('button', { name: 'Go' }));
    expect(onClick).toHaveBeenCalled();
  });

  it('Button loading disables and sets aria-busy', () => {
    render(<Button variant="primary" loading>Save</Button>);
    const b = screen.getByRole('button', { name: /save/i });
    expect(b).toBeDisabled();
    expect(b).toHaveAttribute('aria-busy', 'true');
  });

  it('IconButton requires aria-label', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<IconButton icon="plus" />)).toThrow(/aria-label/);
    spy.mockRestore();
    render(<IconButton icon="plus" aria-label="Add" />);
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
  });

  it('Badge and Skeleton render', () => {
    render(<><Badge tone="ember">Due</Badge><Skeleton lines={2} /></>);
    expect(screen.getByText('Due')).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });
});

describe('molecules', () => {
  it('Field wires label, aria-describedby and invalid', () => {
    render(<Field label="Title" hint="Helpful" error="Required"><Input /></Field>);
    const input = screen.getByLabelText('Title');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    const described = input.getAttribute('aria-describedby');
    expect(described).toBeTruthy();
    expect(document.getElementById(described.split(' ').pop())).toHaveTextContent('Required');
  });

  it('Field with hint only describes by hint', () => {
    render(<Field label="Name" hint="Shown publicly"><Input /></Field>);
    const input = screen.getByLabelText('Name');
    expect(document.getElementById(input.getAttribute('aria-describedby'))).toHaveTextContent('Shown publicly');
  });

  it('ErrorState Retry calls back', () => {
    const onRetry = vi.fn();
    render(<ErrorState message="Couldn't load failures" onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('EmptyState renders its single action', () => {
    const onClick = vi.fn();
    render(<EmptyState title="Nothing here" description="Add one" action={{ label: 'Add', onClick }} />);
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(onClick).toHaveBeenCalled();
  });

  it('SegmentedTabs selects and moves with arrow keys', () => {
    const onChange = vi.fn();
    render(<SegmentedTabs tabs={[{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }]} value="a" onChange={onChange} />);
    expect(screen.getByRole('tab', { name: 'A' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'A' }), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenCalledWith('b');
  });

  it('Card renders header + body', () => {
    render(<Card><Card.Header title="T" subtitle="S" />body</Card>);
    expect(screen.getByText('T')).toBeInTheDocument();
    expect(screen.getByText('body')).toBeInTheDocument();
  });

  it('Disclosure toggles and remembers state per key', () => {
    localStorage.clear();
    const { unmount } = render(<Disclosure title="More" storageKey="t1">inside</Disclosure>);
    expect(screen.queryByText('inside')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /more/i }));
    expect(screen.getByText('inside')).toBeInTheDocument();
    unmount();
    render(<Disclosure title="More" storageKey="t1">inside</Disclosure>);
    expect(screen.getByText('inside')).toBeInTheDocument();
  });
});

describe('Toast', () => {
  beforeEach(() => useStore.setState({ toast: null }));

  it('showToast(string) still works', () => {
    render(<Toast />);
    act(() => useStore.getState().showToast('Hello'));
    expect(screen.getByText('Hello')).toBeInTheDocument();
  });

  it('action button fires and dismisses', () => {
    const onClick = vi.fn();
    render(<Toast />);
    act(() => useStore.getState().showToast({ message: 'Sent', tone: 'success', action: { label: 'View', onClick } }));
    fireEvent.click(screen.getByRole('button', { name: 'View' }));
    expect(onClick).toHaveBeenCalled();
    expect(useStore.getState().toast).toBeNull();
  });

  it('error tone uses role=alert', () => {
    render(<Toast />);
    act(() => useStore.getState().showToast({ message: 'Boom', tone: 'error' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Boom');
  });
});

describe('PageTransition', () => {
  it('renders children', () => {
    render(<PageTransition><p>page</p></PageTransition>);
    expect(screen.getByText('page')).toBeInTheDocument();
  });
});
