import { useEffect, useMemo, useState } from 'react';
import useStore from '../store/useStore';
import { fetchPipelineFailures, resolvePipelineFailure } from '../api/pipelineFailures';
import styles from './PipelineFailuresPage.module.css';

function formatWhen(iso) {
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}

// Best-effort one-line summary of the JSON input_payload for the collapsed row — pick
// whichever field actually identifies the thing that failed (varies by source/stage:
// a URL, a capture id, a title, ...); fall back to the raw JSON, truncated.
function summarize(inputPayload) {
  let obj;
  try { obj = JSON.parse(inputPayload); } catch { return inputPayload || ''; }
  const pick = obj.url || obj.sourceRef || obj.title || obj.captureId || obj.ref;
  if (pick) return String(pick);
  const raw = JSON.stringify(obj);
  return raw.length > 100 ? raw.slice(0, 100) + '…' : raw;
}

function prettyJson(inputPayload) {
  try { return JSON.stringify(JSON.parse(inputPayload), null, 2); } catch { return inputPayload || ''; }
}

export default function PipelineFailuresPage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const showToast = useStore(s => s.showToast);

  const [failures, setFailures] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [onlyOpen, setOnlyOpen] = useState(true);
  const [sourceFilter, setSourceFilter] = useState('');
  const [expanded, setExpanded] = useState(null); // id currently expanded
  const [busyId, setBusyId] = useState(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const list = await fetchPipelineFailures({ onlyOpen });
      setFailures(list);
    } catch (e) {
      setError(e.message ?? String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (isAuthenticated) load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [isAuthenticated, onlyOpen]);

  const sources = useMemo(
    () => [...new Set(failures.map(f => f.source))].sort(),
    [failures],
  );
  const visible = sourceFilter ? failures.filter(f => f.source === sourceFilter) : failures;

  async function handleResolve(id) {
    setBusyId(id);
    try {
      await resolvePipelineFailure(id);
      setFailures(f => f.filter(x => x.id !== id));
    } catch (e) {
      showToast?.(`Resolve failed: ${e.message ?? e}`);
    } finally {
      setBusyId(null);
    }
  }

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <p className={styles.gateText}>Sign in to view pipeline failures.</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Pipeline Failures</h1>
        <button className={styles.refreshBtn} onClick={load} disabled={loading}>
          {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>
      <p className={styles.hint}>
        Every capture/ingest failure that didn't just self-heal — the exact input that
        broke it plus the error, so it's debuggable instead of silently gone. Nothing here
        auto-retries; mark a row resolved once you've looked at it or shipped a fix.
      </p>

      <div className={styles.filters}>
        <label className={styles.filterToggle}>
          <input
            type="checkbox"
            checked={onlyOpen}
            onChange={e => setOnlyOpen(e.target.checked)}
          />
          Open only
        </label>
        <select
          className={styles.filterSelect}
          value={sourceFilter}
          onChange={e => setSourceFilter(e.target.value)}
        >
          <option value="">All sources</option>
          {sources.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      {error && <p className={styles.err}>{error}</p>}

      {!loading && visible.length === 0 && (
        <p className={styles.emptyMsg}>
          {onlyOpen ? 'Nothing open — clean.' : 'No failures recorded.'}
        </p>
      )}

      <div className={styles.list}>
        {visible.map(f => (
          <div key={f.id} className={styles.row}>
            <div className={styles.rowHead} onClick={() => setExpanded(e => e === f.id ? null : f.id)}>
              <div className={styles.rowMain}>
                <span className={styles.badge}>{f.source}</span>
                <span className={styles.badgeStage}>{f.stage}</span>
                <span className={styles.summary}>{summarize(f.inputPayload)}</span>
              </div>
              <span className={styles.when}>{formatWhen(f.occurredAt)}</span>
            </div>
            <p className={styles.errMsg}>{f.errorMessage || f.errorType || 'Unknown error'}</p>

            {expanded === f.id && (
              <pre className={styles.jsonBlock}>{prettyJson(f.inputPayload)}</pre>
            )}

            <div className={styles.rowActions}>
              <button
                className={styles.linkBtn}
                onClick={() => setExpanded(e => e === f.id ? null : f.id)}
              >
                {expanded === f.id ? 'Hide details' : 'Show details'}
              </button>
              {!f.resolvedAt && (
                <button
                  className={styles.resolveBtn}
                  disabled={busyId === f.id}
                  onClick={() => handleResolve(f.id)}
                >
                  Mark resolved
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
