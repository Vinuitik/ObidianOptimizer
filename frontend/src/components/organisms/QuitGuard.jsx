import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import { dutyUnfinished } from '../../utils/dailyDuty';
import { randomQuote } from '../../utils/quotes';
import styles from './QuitGuard.module.css';

// "Are you a quitter?" guard. When today's duty is unfinished — notes still due for
// review OR no Learn item processed today — two things happen:
//   1. beforeunload arms the browser's native "Leave site?" prompt (the ONLY real
//      close-blocker a web page gets — no custom UI is possible at true unload).
//   2. When you return to the tab (visibility → visible) we surface a custom modal
//      with a great-man quote — the actual "are you a quitter?" nag, shown where the
//      browser lets us render. In the desktop app this same modal will fire on the
//      real window-close intercept.
//
// See utils/dailyDuty.js for what counts as "done". Never nags when signed out or on
// verification errors (dutyUnfinished swallows those → treats duty as done).
export default function QuitGuard() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const navigate = useNavigate();
  const [quote, setQuote] = useState(null); // non-null ⇒ modal open
  const unfinishedRef = useRef(false);      // latest known duty state, for beforeunload

  // Keep an up-to-date "still owes work" flag while signed in: on mount, on tab
  // refocus, and whenever auth flips. beforeunload reads it synchronously (it can't
  // await), so the async probe result is stashed in a ref.
  useEffect(() => {
    if (!isAuthenticated) { unfinishedRef.current = false; return; }
    let cancelled = false;
    const refresh = () => dutyUnfinished().then(v => { if (!cancelled) unfinishedRef.current = v; });
    refresh();

    const onVisible = () => {
      if (document.visibilityState !== 'visible' || !isAuthenticated) return;
      dutyUnfinished().then(v => {
        if (cancelled) return;
        unfinishedRef.current = v;
        if (v) setQuote(randomQuote());   // came back with work outstanding → nag
      });
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => { cancelled = true; document.removeEventListener('visibilitychange', onVisible); };
  }, [isAuthenticated]);

  // Native close-guard. Present only while duty is outstanding, so a caught-up user
  // never sees a spurious "Leave site?" prompt.
  useEffect(() => {
    const onBeforeUnload = (e) => {
      if (!unfinishedRef.current) return;
      e.preventDefault();
      e.returnValue = '';   // required for the prompt to show in Chrome
      return '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, []);

  if (!quote) return null;

  const backToWork = () => { setQuote(null); navigate('/review'); };
  const leave      = () => setQuote(null);

  return (
    <div className={styles.overlay} onClick={leave}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <span className={styles.title}>Are you a quitter?</span>
        <p className={styles.subtitle}>
          You still have reviews due or Learn items to process today.
        </p>
        <blockquote className={styles.quote}>
          “{quote.text}”
          <cite className={styles.cite}>— {quote.author}</cite>
        </blockquote>
        <div className={styles.actions}>
          <Button variant="ghost" onClick={leave}>I'm a quitter</Button>
          <Button variant="primary" onClick={backToWork}>No — back to work</Button>
        </div>
      </div>
    </div>
  );
}
