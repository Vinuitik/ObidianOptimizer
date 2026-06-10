import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LoginModal from './LoginModal';

// ── Store mock ────────────────────────────────────────────────────────────────

const mockLogin        = vi.fn();
const mockSetShowLogin = vi.fn();

vi.mock('../../store/useStore', () => ({
  default: vi.fn((selector) => selector({
    login:        mockLogin,
    setShowLogin: mockSetShowLogin,
  })),
}));

vi.mock('../atoms/Button', () => ({
  default: ({ children, onClick, type }) => (
    <button type={type ?? 'button'} onClick={onClick}>{children}</button>
  ),
}));

beforeEach(() => {
  vi.clearAllMocks();
});

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('LoginModal', () => {
  it('renders username and password inputs', () => {
    render(<LoginModal />);
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
  });

  it('renders Sign in button and Cancel button', () => {
    render(<LoginModal />);
    // The modal title span and submit button both contain "Sign in" — query by role
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('typing in username field updates its value', () => {
    render(<LoginModal />);
    const input = screen.getByLabelText(/username/i);
    fireEvent.change(input, { target: { value: 'admin' } });
    expect(input.value).toBe('admin');
  });

  it('submitting form calls login with username and password', async () => {
    mockLogin.mockResolvedValue(true);
    render(<LoginModal />);
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'admin' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => expect(mockLogin).toHaveBeenCalledWith('admin', 'secret'));
  });

  it('shows error message when login returns false', async () => {
    mockLogin.mockResolvedValue(false);
    render(<LoginModal />);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(screen.getByText(/invalid credentials/i)).toBeInTheDocument());
  });

  it('does not show error message before submit', () => {
    render(<LoginModal />);
    expect(screen.queryByText(/invalid credentials/i)).toBeNull();
  });

  it('Cancel button calls setShowLogin(false)', () => {
    render(<LoginModal />);
    fireEvent.click(screen.getByText('Cancel'));
    expect(mockSetShowLogin).toHaveBeenCalledWith(false);
  });

  it('clicking the overlay calls setShowLogin(false)', () => {
    const { container } = render(<LoginModal />);
    // Overlay is the outermost div; click it directly (not the modal inside)
    fireEvent.click(container.firstChild);
    expect(mockSetShowLogin).toHaveBeenCalledWith(false);
  });
});
