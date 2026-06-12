import { useEffect, useRef, useState } from 'react';
import { PieChart, Pie, Cell, ResponsiveContainer } from 'recharts';
import { fetchStats } from '../api/stats';
import styles from './DashboardPage.module.css';

const POLL_MS = 3000;

const COLORS = {
  done:    'var(--color-success)',
  pending: 'var(--color-accent)',
  skipped: 'var(--color-amber)',
  rest:    'rgba(255, 255, 255, 0.08)',
};

function Donut({ slices, centerLabel, centerSub }) {
  const data = slices.filter(s => s.value > 0);
  const empty = data.length === 0;
  return (
    <div className={styles.donutWrap}>
      <ResponsiveContainer width="100%" height={180}>
        <PieChart>
          <Pie
            data={empty ? [{ name: 'empty', value: 1 }] : data}
            dataKey="value"
            innerRadius={58}
            outerRadius={80}
            startAngle={90}
            endAngle={-270}
            stroke="none"
            isAnimationActive={false}
          >
            {(empty ? [{ color: COLORS.rest }] : data).map((s, i) => (
              <Cell key={i} fill={s.color} />
            ))}
          </Pie>
        </PieChart>
      </ResponsiveContainer>
      <div className={styles.donutCenter}>
        <span className={styles.donutValue}>{centerLabel}</span>
        {centerSub && <span className={styles.donutSub}>{centerSub}</span>}
      </div>
    </div>
  );
}

function Legend({ items }) {
  return (
    <ul className={styles.legend}>
      {items.map(({ label, value, color }) => (
        <li key={label}>
          <span className={styles.legendDot} style={{ background: color }} />
          {label}
          <span className={styles.legendValue}>{value}</span>
        </li>
      ))}
    </ul>
  );
}

function pct(part, total) {
  if (!total) return '—';
  return `${Math.round((part / total) * 100)}%`;
}

export default function DashboardPage() {
  const [stats, setStats] = useState(null);
  const [error, setError] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);
  const timerRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    async function poll() {
      // skip work while the tab is hidden — counters resume on focus
      if (!document.hidden) {
        try {
          const data = await fetchStats();
          if (cancelled) return;
          setStats(data);
          setError(null);
          setUpdatedAt(new Date());
        } catch (e) {
          if (cancelled) return;
          setError(e.message);
        }
      }
      timerRef.current = setTimeout(poll, POLL_MS);
    }
    poll();
    return () => {
      cancelled = true;
      clearTimeout(timerRef.current);
    };
  }, []);

  if (!stats) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>Processing Dashboard</h1>
        <p className={styles.muted}>{error ? `Failed to load stats: ${error}` : 'Loading…'}</p>
      </div>
    );
  }

  const { embedding, images, flashcards, wrapper } = stats;
  const notesRemaining = embedding.notesTotal - embedding.notesEmbedded;
  const imagesTotal = images.pending + images.done + images.skipped;
  const cardCoverage = flashcards.eligibleNotes
    ? Math.min(100, Math.round((flashcards.notesWithCards / flashcards.eligibleNotes) * 100))
    : 0;
  const providers = wrapper?.providers ?? null;

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Processing Dashboard</h1>
        <span className={styles.live}>
          <span className={error ? styles.liveDotStale : styles.liveDot} />
          {error
            ? `stale — ${error}`
            : updatedAt ? `live · updated ${updatedAt.toLocaleTimeString()}` : 'live'}
        </span>
      </div>

      <div className={styles.grid}>
        {/* 1. Vector embedding progress */}
        <section className={styles.card}>
          <h2 className={styles.cardTitle}>Note Embedding</h2>
          <Donut
            slices={[
              { name: 'embedded', value: embedding.notesEmbedded, color: COLORS.done },
              { name: 'remaining', value: notesRemaining, color: COLORS.rest },
            ]}
            centerLabel={pct(embedding.notesEmbedded, embedding.notesTotal)}
            centerSub="embedded"
          />
          <Legend items={[
            { label: 'Embedded notes', value: embedding.notesEmbedded, color: COLORS.done },
            { label: 'Remaining', value: notesRemaining, color: COLORS.rest },
            { label: 'Total chunks', value: embedding.chunksTotal, color: 'transparent' },
          ]} />
        </section>

        {/* 2. Image pipeline queue */}
        <section className={styles.card}>
          <h2 className={styles.cardTitle}>Image Processing</h2>
          <Donut
            slices={[
              { name: 'done', value: images.done, color: COLORS.done },
              { name: 'pending', value: images.pending, color: COLORS.pending },
              { name: 'skipped', value: images.skipped, color: COLORS.skipped },
            ]}
            centerLabel={pct(images.done, imagesTotal)}
            centerSub={`${images.done} / ${imagesTotal}`}
          />
          <Legend items={[
            { label: 'Done', value: images.done, color: COLORS.done },
            { label: 'In queue', value: images.pending, color: COLORS.pending },
            { label: 'Skipped (daily retry)', value: images.skipped, color: COLORS.skipped },
          ]} />
        </section>

        {/* 3. Flashcard coverage */}
        <section className={styles.card}>
          <h2 className={styles.cardTitle}>Flashcard Coverage</h2>
          <div className={styles.bigStat}>
            {flashcards.notesWithCards}
            <span className={styles.bigStatDenom}> / {flashcards.eligibleNotes} eligible notes</span>
          </div>
          <div className={styles.progressTrack}>
            <div className={styles.progressFill} style={{ width: `${cardCoverage}%` }} />
          </div>
          <Legend items={[
            { label: 'Active cards', value: flashcards.activeCards, color: COLORS.done },
            { label: 'Archived cards', value: flashcards.archivedCards, color: COLORS.rest },
          ]} />
        </section>

        {/* 4. LLM provider health (host-wrapper router) */}
        <section className={styles.card}>
          <h2 className={styles.cardTitle}>LLM Providers</h2>
          {!wrapper?.up && (
            <p className={styles.warn}>Host wrapper offline — image processing paused.</p>
          )}
          {providers && (
            <table className={styles.providerTable}>
              <thead>
                <tr><th>provider</th><th>state</th><th>ok</th><th>failed</th></tr>
              </thead>
              <tbody>
                {Object.entries(providers).map(([name, p]) => (
                  <tr key={name} className={p.configured ? '' : styles.providerOff}>
                    <td>{name}</td>
                    <td>
                      {!p.configured ? 'no key'
                        : p.cooldown_s > 0 ? `cooling ${p.cooldown_s}s`
                        : p.in_flight > 0 ? 'working'
                        : 'ready'}
                    </td>
                    <td>{p.ok}</td>
                    <td>{p.failed}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {/* 5. External resource queue — ingest agent not built yet */}
        <section className={`${styles.card} ${styles.cardDisabled}`}>
          <h2 className={styles.cardTitle}>Video &amp; Resource Queue</h2>
          <p className={styles.muted}>Ingest agent not implemented yet (INGEST_AGENT_ARCH).</p>
        </section>
      </div>
    </div>
  );
}
