import { useEffect, useState } from 'react';
import Button from '../components/atoms/Button';
import { onInstallChange, promptInstall } from '../pwa/installPrompt';
import styles from './GetAppPage.module.css';

// One place to get the app on any device: install the PWA (native prompt where the browser
// supports it) or download the desktop build (the Electron "quit-breaker" shell). The desktop
// installer is served by nginx at /download/ (see nginx.conf.template + docker-compose mount).
const WIN_INSTALLER = '/download/ObsidianOptimizer-Setup.exe';

export default function GetAppPage() {
  const [{ canInstall, installed }, setInstall] = useState({ canInstall: false, installed: false });
  const [msg, setMsg] = useState('');

  useEffect(() => onInstallChange(setInstall), []);

  async function install() {
    const outcome = await promptInstall();
    if (outcome === 'accepted') setMsg('Installing… check your taskbar / home screen.');
    else if (outcome === 'unavailable') setMsg('');   // fall back to the manual note below
    else setMsg('');
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Get the app</h1>
      <p className={styles.lede}>
        Run Obsidian Optimizer in its own window — no browser chrome, offline-capable, and with the
        “Are you a quitter?” close-guard.
      </p>

      <p className={styles.note}>
        A note on browser support: installing a web app to your desktop is only offered by
        <strong> Chrome</strong> and <strong>Edge</strong> — Firefox and Safari don’t support it. For
        the smoothest install, open this page in Chrome. On Firefox or Safari, use the Windows
        download below instead. Sorry for the hoops — it’s a browser limitation, not by choice.
      </p>

      <div className={styles.cards}>
        {/* ── Install as PWA ─────────────────────────────────────────────── */}
        <section className={styles.card}>
          <span className={styles.badge}>Any device</span>
          <h2 className={styles.cardTitle}>Install as app</h2>
          <p className={styles.cardBody}>
            Adds Obsidian Optimizer to your home screen / taskbar as a standalone app. Works on phone
            and desktop. Fastest option — nothing to download.
          </p>
          {installed ? (
            <div className={styles.installed}>✓ Already installed on this device</div>
          ) : canInstall ? (
            <Button variant="primary" onClick={install}>Install app</Button>
          ) : (
            <p className={styles.hint}>
              In <strong>Chrome or Edge</strong>, use the install icon in the address bar (a monitor
              with a ↓), or ⋮ menu → “Install this site as an app”. <strong>Firefox and Safari</strong>
              desktop can’t install web apps — use the desktop download instead.
            </p>
          )}
          {msg && <p className={styles.msg}>{msg}</p>}
        </section>

        {/* ── Desktop download ──────────────────────────────────────────── */}
        <section className={styles.card}>
          <span className={styles.badge}>Windows</span>
          <h2 className={styles.cardTitle}>Download for Windows</h2>
          <p className={styles.cardBody}>
            A native installer with the real close-guard: when you try to quit with reviews or Learn
            items still pending, it pressures you with a quote before letting you leave.
          </p>
          <a className={styles.dlBtn} href={WIN_INSTALLER} download>Download for Windows</a>
          <p className={styles.hint}>
            Unsigned build — on first run Windows may warn “Windows protected your PC”. Click
            <strong> More info → Run anyway</strong>. Needs the server reachable (it loads the live site).
          </p>
        </section>
      </div>
    </div>
  );
}
