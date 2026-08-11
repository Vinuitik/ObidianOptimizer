import { useEffect, useState } from 'react';
import Button from '../components/atoms/Button';
import btnStyles from '../components/atoms/Button.module.css';
import { onInstallChange, promptInstall, isIOS, isWindows } from '../pwa/installPrompt';
import styles from './GetAppPage.module.css';

// One place to get the app, one single CTA — which one depends on the device:
//   Windows          → the Electron desktop build (real OS-level close-guard, no browser
//                       dependency — strictly richer than the PWA, so it always wins here).
//   Android / other   → the native PWA install prompt where the browser offers it.
//   iOS / no prompt   → a button that reveals the manual steps on tap (iOS never gets a
//                       real one-tap install — no browser API exists for it, ever).
//   already installed → no button, just a status line.
// The desktop installer is served by nginx at /download/ (see nginx.conf.template + docker-compose mount).
const WIN_INSTALLER = '/download/ObsidianOptimizer-Setup.exe';

export default function GetAppPage() {
  const [{ canInstall, installed }, setInstall] = useState({ canInstall: false, installed: false });
  const [msg, setMsg] = useState('');
  const [showSteps, setShowSteps] = useState(false);

  useEffect(() => onInstallChange(setInstall), []);

  async function install() {
    const outcome = await promptInstall();
    if (outcome === 'accepted') setMsg('Installing… check your taskbar / home screen.');
    else setMsg('');
  }

  const steps = isIOS()
    ? 'Tap the Share icon in Safari, then "Add to Home Screen".'
    : 'This browser doesn’t support one-tap install. On Android, check Chrome’s ⋮ menu for "Install app". On desktop, try Chrome or Edge and look for an install icon in the address bar.';

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Get the app</h1>
      <p className={styles.lede}>
        Run Obsidian Optimizer in its own window — no browser chrome, offline-capable, and with the
        “Are you a quitter?” close-guard.
      </p>

      <section className={styles.card}>
        <span className={styles.badge}>{isWindows() ? 'Windows' : 'This device'}</span>
        <h2 className={styles.cardTitle}>Install as app</h2>
        <p className={styles.cardBody}>
          Adds Obsidian Optimizer to your home screen / taskbar as a standalone app.
        </p>

        {installed ? (
          <div className={styles.installed}>✓ Already installed on this device</div>
        ) : isWindows() ? (
          <>
            <a className={`${btnStyles.btn} ${btnStyles.primary}`} href={WIN_INSTALLER} download>
              Download for Windows
            </a>
            <p className={styles.hint}>
              Unsigned build — on first run Windows may warn “Windows protected your PC”. Click
              <strong> More info → Run anyway</strong>. Needs the server reachable (it loads the live site).
            </p>
          </>
        ) : canInstall ? (
          <Button variant="primary" onClick={install}>Install app</Button>
        ) : (
          <>
            <Button variant="primary" onClick={() => setShowSteps(v => !v)}>How to install</Button>
            {showSteps && <p className={styles.hint}>{steps}</p>}
          </>
        )}
        {msg && <p className={styles.msg}>{msg}</p>}
      </section>
    </div>
  );
}
