import { useState } from 'react';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import Input from '../atoms/Input';
import Field from '../molecules/Field';
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
        <form className={styles.form} onSubmit={handleSubmit}>
          <Field label="Username">
            <Input
              id="login-username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoFocus
              autoComplete="username"
            />
          </Field>
          <Field label="Password" error={error}>
            <Input
              id="login-password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </Field>
          <div className={styles.actions}>
            <Button variant="ghost" onClick={() => setShowLogin(false)}>Cancel</Button>
            <Button variant="primary" type="submit">Sign in</Button>
          </div>
        </form>
      </div>
    </div>
  );
}
