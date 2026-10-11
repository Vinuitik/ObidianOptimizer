import { useEffect, useRef, useState } from 'react';
import { PieChart, Pie, Cell, ResponsiveContainer } from 'recharts';
import { fetchStats } from '../api/stats';
import useStore from '../store/useStore';
import Card from '../components/molecules/Card';
import PageHeader from '../components/molecules/PageHeader';
import ErrorState from '../components/molecules/ErrorState';
import Skeleton from '../components/atoms/Skeleton';
import styles from './DashboardPage.module.css';

const POLL_MS = 3000;

const COLORS = {
  done:    'var(--success)',
  pending: 'var(--accent)',
  skipped: 'var(--ember)',
  rest:    'var(--line-strong)',
};

function Donut({ slices, centerLabel, centerSub }) {
  const data = slices.filter(s => s.value > 0);
  const empty = data.length === 0;
  return (
    <div className={styles.donutWrap}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={empty ? [{ name: 'empty', value: 1 }] : data}
            dataKey="value"
            innerRadius="72%"
            outerRadius="100%"
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
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [stats, setStats] = useState(null);
  const [error, setError] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [retryKey, setRetryKey] = useState(0);
  const timerRef = useRef(null);

  useEffect(() => {
    if (!isAuthenticated) return;
    let cancelled = false;
    async function poll() {
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
  }, [isAuthenticated, retryKey]);

  if (!isAuthenticated) {
    return (
      <div className={styles.page}>
        <PageHeader title="Processing" />
        <p className={styles.muted}>Sign in to view the dashboard.</p>
      </div>
    );
  }

  if (!stats) {
    return (
      <div className={styles.page}>
        <PageHeader title="Processing" />
        {error ? (
          <ErrorState message="Couldn’t load stats" detail={error} onRetry={() => setRetryKey(k => k + 1)} />
        ) : (
          <div className={styles.grid}>
            {[0, 1, 2, 3].map(i => (
              <Card key={i} className={styles.card}><Skeleton height={140} /></Card>
            ))}
          </div>
        )}
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
      <PageHeader
        title="Processing"
        actions={
          <span className={styles.live} role="status">
            <span className={error ? styles.liveDotStale : styles.liveDot} />
            {error
              ? 'Stale — can’t reach the server'
              : updatedAt ? `Live · ${updatedAt.toLocaleTimeString()}` : 'Live'}
          </span>
        }
      />

      <div className={styles.grid}>
        {/* 1. Vector embedding progress */}
        <Card as="section" className={styles.card}>
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
        </Card>

        {/* 2. Image pipeline queue */}
        <Card as="section" className={styles.card}>
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
        </Card>

        {/* 3. Flashcard coverage */}
        <Card as="section" className={styles.card}>
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
        </Card>

        {/* 4. LLM provider health (host-wrapper router) */}
        <Card as="section" className={styles.card}>
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
        </Card>

        {/* 5. External resource queue — ingest agent jobs (embedder /ingest) */}
        <Card as="section" className={stats.resources?.up ? styles.card : `${styles.card} ${styles.cardDisabled}`}>
          <h2 className={styles.cardTitle}>Video &amp; Resource Queue</h2>
          {!stats.resources?.up ? (
            <p className={styles.muted}>Ingest agent offline (embedder unreachable).</p>
          ) : (
            <>
              <p>
                {['QUEUED', 'RUNNING', 'DONE', 'FAILED'].map((s) => (
                  <span key={s} className={styles.queueStat}>
                    {s.toLowerCase()}: <strong>{stats.resources.counts?.[s] ?? 0}</strong>
                  </span>
                ))}
              </p>
              {(stats.resources.recent ?? []).map((j) => (
                <p key={j.id} className={styles.muted}>
                  {j.status} · {j.stage ?? ''} · {j.ref}
                  {j.notes_created?.length ? ` → ${j.notes_created.length} note(s)` : ''}
                </p>
              ))}
            </>
          )}
        </Card>
      </div>
    </div>
  );
}
