import { useState } from 'react';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import styles from './LoginModal.module.css';

export default function LoginModal() {
  const login = useStore(s => s.login);
  const setShowLogin = useStore(s => s.setShowLogin);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    const ok = await login(username, password);
    if (!ok) setError('Invalid credentials');
  };

  return (
    <div className={styles.overlay} onClick={() => setShowLogin(false)}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <span className={styles.title}>Sign in</span>
        <form onSubmit={handleSubmit}>
          <div className={styles.field}>
            <label className={styles.label}>Username</label>
            <input
              className={styles.input}
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoFocus
              autoComplete="username"
            />
          </div>
          <div className={styles.field} style={{ marginTop: 12 }}>
            <label className={styles.label}>Password</label>
            <input
              className={styles.input}
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </div>
          {error && <p className={styles.error}>{error}</p>}
          <div className={styles.actions} style={{ marginTop: 16 }}>
            <Button variant="ghost" onClick={() => setShowLogin(false)}>Cancel</Button>
            <Button type="submit">Sign in</Button>
          </div>
        </form>
      </div>
    </div>
  );
}
