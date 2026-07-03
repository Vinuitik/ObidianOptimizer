import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { tokenize, dwellMs, splitAtOrp } from '../../utils/rsvp';
import styles from './RsvpReader.module.css';

// RSVP reader (INGESTION_V2_FLOWS §7): flashes one word at a time at a fixed point with
// the ORP letter highlighted, so reading is saccade-free and fast. Drives the pure
// helpers in utils/rsvp.js on a self-scheduling timer (each word's dwell depends on its
// length + trailing punctuation). `text` is raw note markdown; scaffolding is stripped.
export default function RsvpReader({ text }) {
  const words = useMemo(() => tokenize(text), [text]);
  const [i, setI] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [wpm, setWpm] = useState(350);
  const timer = useRef(null);

  useEffect(() => { setI(0); setPlaying(false); }, [text]);

  useEffect(() => {
    if (!playing) return undefined;
    if (i >= words.length) { setPlaying(false); return undefined; }
    timer.current = setTimeout(() => setI(n => n + 1), dwellMs(words[i], wpm));
    return () => clearTimeout(timer.current);
  }, [playing, i, words, wpm]);

  const toggle = useCallback(() => {
    setI(n => (n >= words.length ? 0 : n));
    setPlaying(p => !p);
  }, [words.length]);

  if (!words.length) return <div className={styles.empty}>No text to read.</div>;

  const [before, pivot, after] = splitAtOrp(words[Math.min(i, words.length - 1)]);
  const done = i >= words.length;

  return (
    <div className={styles.rsvp}>
      <div className={styles.stageWrap}>
        <div className={styles.guide} aria-hidden />
        <div className={styles.stage}>
          <span className={styles.before}>{before}</span>
          <span className={styles.pivot}>{pivot}</span>
          <span className={styles.after}>{after}</span>
        </div>
        <div className={styles.guide} aria-hidden />
      </div>

      <div className={styles.controls}>
        <button className={styles.play} onClick={toggle} aria-label={playing ? 'Pause' : 'Play'}>
          {playing ? '❚❚' : done ? '↻' : '▶'}
        </button>
        <input
          className={styles.wpm}
          type="range" min="150" max="800" step="25"
          value={wpm} onChange={e => setWpm(Number(e.target.value))}
          aria-label="Words per minute"
        />
        <span className={styles.wpmLabel}>{wpm} wpm</span>
        <span className={styles.count}>{Math.min(i + 1, words.length)}/{words.length}</span>
      </div>

      <input
        className={styles.scrub}
        type="range" min="0" max={words.length - 1}
        value={Math.min(i, words.length - 1)}
        onChange={e => { setPlaying(false); setI(Number(e.target.value)); }}
        aria-label="Scrub position"
      />
    </div>
  );
}
