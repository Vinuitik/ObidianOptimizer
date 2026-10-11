import { useCallback, useEffect, useMemo, useState } from 'react';
import useStore from '../store/useStore';
import { fetchPipelineFailures, resolvePipelineFailure } from '../api/pipelineFailures';
import { fetchFailedCaptures, retryCapture, dismissCapture } from '../api/capture';
import Button from '../components/atoms/Button';
import Badge from '../components/atoms/Badge';
import Checkbox from '../components/atoms/Checkbox';
import Select from '../components/atoms/Select';
import Skeleton from '../components/atoms/Skeleton';
import Card from '../components/molecules/Card';
import PageHeader from '../components/molecules/PageHeader';
import EmptyState from '../components/molecules/EmptyState';
import ErrorState from '../components/molecules/ErrorState';
import styles from './PipelineFailuresPage.module.css';

const WEEK_MS = 7 * 24 * 3600 * 1000;

function formatWhen(iso) {
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}

// "youtube_channel" -> "Youtube channel"; backend enums never reach the screen raw.
export function humanize(s) {
  const t = String(s ?? '').replace(/[_-]+/g, ' ').trim();
  return t ? t[0].toUpperCase() + t.slice(1) : '';
}

// Bare status codes ("502") are not a message — only show detail that says something.
export function errorDetail(e) {
  const m = e?.message ?? String(e ?? '');
  return /^\d+$/.test(m) ? undefined : m;
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

// One independently-loaded list: { status: 'loading' | 'ok' | 'error', items, error }.
function useList(loader, enabled, deps = []) {
  const [state, setState] = useState({ status: 'loading', items: [], error: null });
  const load = useCallback(async () => {
    setState(s => ({ ...s, status: 'loading', error: null }));
    try {
      setState({ status: 'ok', items: await loader(), error: null });
    } catch (e) {
      setState({ status: 'error', items: [], error: e });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => { if (enabled) load(); }, [enabled, load]);
  const setItems = useCallback(
    fn => setState(s => ({ ...s, items: typeof fn === 'function' ? fn(s.items) : fn })), []);
  return [state, load, setItems];
}

export default function PipelineFailuresPage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const showToast = useStore(s => s.showToast);
  const loadFailuresCount = useStore(s => s.loadFailuresCount);

  const [onlyOpen, setOnlyOpen] = useState(true);
  const [sourceFilter, setSourceFilter] = useState('');
  const [expanded, setExpanded] = useState(null);
  const [busyId, setBusyId] = useState(null);       // pipeline row id
  const [busyCapture, setBusyCapture] = useState(null); // `${id}:retry|dismiss`
  const [resolvingOld, setResolvingOld] = useState(false);

  const [captures, reloadCaptures, setCaptures] = useList(fetchFailedCaptures, isAuthenticated);
  const [pipeline, reloadPipeline, setPipeline] = useList(
    () => fetchPipelineFailures({ onlyOpen }), isAuthenticated, [onlyOpen]);

  const refreshCount = () => loadFailuresCount?.();

  function reloadAll() { reloadCaptures(); reloadPipeline(); refreshCount(); }

  const failures = pipeline.items;
  const sources = useMemo(() => [...new Set(failures.map(f => f.source))].sort(), [failures]);
  const visible = sourceFilter ? failures.filter(f => f.source === sourceFilter) : failures;
  const stale = useMemo(
    () => failures.filter(f => !f.resolvedAt && Date.now() - new Date(f.occurredAt).getTime() > WEEK_MS),
    [failures]);

  async function handleCaptureAction(c, kind) {
    setBusyCapture(`${c.id}:${kind}`);
    try {
      await (kind === 'retry' ? retryCapture(c.id) : dismissCapture(c.id));
      setCaptures(list => list.filter(x => x.id !== c.id));
      showToast?.(kind === 'retry' ? 'Retry queued — it will reappear here if it fails again.' : 'Dismissed.');
      refreshCount();
    } catch (e) {
      showToast?.({
        message: `Couldn’t ${kind} that capture.`, tone: 'error',
        action: { label: 'Try again', onClick: () => handleCaptureAction(c, kind) },
      });
    } finally {
      setBusyCapture(null);
    }
  }

  async function handleResolve(id) {
    setBusyId(id);
    try {
      await resolvePipelineFailure(id);
      setPipeline(list => list.filter(x => x.id !== id));
      refreshCount();
    } catch {
      showToast?.({ message: 'Couldn’t mark that resolved.', tone: 'error' });
    } finally {
      setBusyId(null);
    }
  }

  // Client-side loop over the existing per-row endpoint; keeps whatever failed in the list.
  async function resolveOlder() {
    setResolvingOld(true);
    const done = new Set();
    for (const f of stale) {
      try { await resolvePipelineFailure(f.id); done.add(f.id); } catch { /* keep row */ }
    }
    setPipeline(list => list.filter(x => !done.has(x.id)));
    setResolvingOld(false);
    refreshCount();
    const failed = stale.length - done.size;
    showToast?.(failed
      ? { message: `Resolved ${done.size}; ${failed} couldn’t be resolved.`, tone: 'error' }
      : `Resolved ${done.size} older failure${done.size === 1 ? '' : 's'}.`);
  }

  if (!isAuthenticated) {
    return (
      <div className={styles.page}>
        <PageHeader title="Failures" />
        <p className={styles.muted}>Sign in to view failures.</p>
      </div>
    );
  }

  const busy = captures.status === 'loading' || pipeline.status === 'loading';

  return (
    <div className={styles.page}>
      <PageHeader
        title="Failures"
        subtitle="Things that didn’t make it through. Retry a capture, or mark a failure resolved once you’ve looked at it."
        actions={<Button size="sm" onClick={reloadAll} loading={busy}>Refresh</Button>}
      />

      {/* ── Failed captures ───────────────────────────────────────────────── */}
      <section className={styles.section} aria-labelledby="fc-title">
        <div className={styles.sectionHead}>
          <h2 id="fc-title" className={styles.sectionTitle}>Failed captures</h2>
          {captures.status === 'ok' && captures.items.length > 0 && (
            <Badge tone="danger">{captures.items.length}</Badge>
          )}
        </div>
        <p className={styles.sectionHint}>Links and files the app couldn’t process. It keeps retrying on its own; retry now to skip the wait.</p>

        {captures.status === 'loading' && <Skeleton lines={3} height={48} />}
        {captures.status === 'error' && (
          <ErrorState message="Couldn’t load failed captures" detail={errorDetail(captures.error)} onRetry={reloadCaptures} />
        )}
        {captures.status === 'ok' && captures.items.length === 0 && (
          <EmptyState icon="check" title="No failed captures" description="Everything you captured went through." />
        )}
        {captures.status === 'ok' && captures.items.length > 0 && (
          <div className={styles.list}>
            {captures.items.map(c => (
              <Card key={c.id} className={styles.row}>
                <div className={styles.rowHead}>
                  <div className={styles.rowMain}>
                    <Badge tone="neutral">{humanize(c.sourceType)}</Badge>
                    <span className={styles.summary}>{c.title || c.sourceRef}</span>
                  </div>
                  {c.createdAt && <span className={styles.when}>{formatWhen(c.createdAt)}</span>}
                </div>
                {c.title && c.sourceRef && <p className={styles.ref}>{c.sourceRef}</p>}
                <p className={styles.errMsg}>
                  {c.lastError || 'Unknown error'}
                  {c.retryCount > 0 && <span className={styles.retries}> · tried {c.retryCount}×</span>}
                </p>
                <div className={styles.rowActions}>
                  <Button
                    size="sm" variant="primary"
                    loading={busyCapture === `${c.id}:retry`}
                    disabled={busyCapture != null}
                    onClick={() => handleCaptureAction(c, 'retry')}
                  >
                    Retry
                  </Button>
                  <Button
                    size="sm" variant="ghost"
                    loading={busyCapture === `${c.id}:dismiss`}
                    disabled={busyCapture != null}
                    onClick={() => handleCaptureAction(c, 'dismiss')}
                  >
                    Dismiss
                  </Button>
                </div>
              </Card>
            ))}
          </div>
        )}
      </section>

      {/* ── Pipeline failures ─────────────────────────────────────────────── */}
      <section className={styles.section} aria-labelledby="pf-title">
        <div className={styles.sectionHead}>
          <h2 id="pf-title" className={styles.sectionTitle}>Pipeline failures</h2>
          {pipeline.status === 'ok' && failures.length > 0 && <Badge tone="danger">{failures.length}</Badge>}
        </div>
        <p className={styles.sectionHint}>
          Import steps that broke, with the exact input and error so they’re debuggable. Nothing here retries itself.
        </p>

        <div className={styles.filters}>
          <Checkbox
            id="pf-open-only"
            label="Open only"
            checked={onlyOpen}
            onChange={e => setOnlyOpen(e.target.checked)}
          />
          <Select
            aria-label="Source"
            value={sourceFilter}
            onChange={e => setSourceFilter(e.target.value)}
            options={[{ value: '', label: 'All sources' }, ...sources.map(s => ({ value: s, label: humanize(s) }))]}
          />
          {onlyOpen && stale.length > 0 && (
            <Button size="sm" variant="secondary" loading={resolvingOld} onClick={resolveOlder}>
              Resolve all older than 7 days ({stale.length})
            </Button>
          )}
        </div>

        {pipeline.status === 'loading' && <Skeleton lines={3} height={48} />}
        {pipeline.status === 'error' && (
          <ErrorState message="Couldn’t load pipeline failures" detail={errorDetail(pipeline.error)} onRetry={reloadPipeline} />
        )}
        {pipeline.status === 'ok' && visible.length === 0 && (
          <EmptyState
            icon="check"
            title={onlyOpen ? 'No open failures' : 'No failures recorded'}
            description={onlyOpen ? 'Nothing needs your attention here.' : undefined}
          />
        )}
        {pipeline.status === 'ok' && visible.length > 0 && (
          <div className={styles.list}>
            {visible.map(f => (
              <Card key={f.id} className={styles.row}>
                <div className={styles.rowHead}>
                  <div className={styles.rowMain}>
                    <Badge tone="accent">{humanize(f.source)}</Badge>
                    <span className={styles.stage}>{humanize(f.stage)}</span>
                    <span className={styles.summary}>{summarize(f.inputPayload)}</span>
                  </div>
                  <span className={styles.when}>{formatWhen(f.occurredAt)}</span>
                </div>
                <p className={styles.errMsg}>{f.errorMessage || f.errorType || 'Unknown error'}</p>

                {expanded === f.id && <pre className={styles.jsonBlock}>{prettyJson(f.inputPayload)}</pre>}

                <div className={styles.rowActions}>
                  <Button size="sm" variant="ghost" onClick={() => setExpanded(e => (e === f.id ? null : f.id))}>
                    {expanded === f.id ? 'Hide details' : 'Show details'}
                  </Button>
                  {!f.resolvedAt && (
                    <Button
                      size="sm"
                      loading={busyId === f.id}
                      disabled={busyId != null}
                      onClick={() => handleResolve(f.id)}
                    >
                      Mark resolved
                    </Button>
                  )}
                </div>
              </Card>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
