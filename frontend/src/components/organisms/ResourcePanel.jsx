import { useState, useEffect } from 'react';
import { fetchWorkspaceFiles } from '../../api/workspace';
import useStore from '../../store/useStore';
import styles from './ResourcePanel.module.css';

const TABS = [
  { value: 'pdf',   label: 'PDF' },
  { value: 'video', label: 'Video' },
  { value: 'audio', label: 'Audio' },
];

export default function ResourcePanel({ resourceType, onResourceTypeChange, onVideoOrientation }) {
  const isAuthenticated = useStore(s => s.isAuthenticated);

  const [files,    setFiles]    = useState([]);
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState(null);
  const [selected, setSelected] = useState(null);

  useEffect(() => {
    if (!isAuthenticated) return;
    setLoading(true);
    setError(null);
    fetchWorkspaceFiles()
      .then(setFiles)
      .catch(() => setError('Could not load workspace files.'))
      .finally(() => setLoading(false));
  }, [isAuthenticated]);

  const visible = files.filter(f => f.type === resourceType);

  function switchType(type) {
    onResourceTypeChange(type);
    setSelected(null);
    onVideoOrientation?.(null);   // unknown until the next video's metadata loads
  }

  return (
    <div className={styles.panel}>
      <div className={styles.tabs}>
        {TABS.map(t => (
          <button
            key={t.value}
            className={`${styles.tab} ${resourceType === t.value ? styles.tabActive : ''}`}
            onClick={() => switchType(t.value)}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className={styles.body}>
        <div className={styles.fileList}>
          {loading && <p className={styles.status}>Loading…</p>}
          {error   && <p className={styles.statusError}>{error}</p>}
          {!loading && !error && visible.length === 0 && (
            <p className={styles.status}>No {resourceType} files in _workspace/</p>
          )}
          {visible.map(f => (
            <button
              key={f.name}
              className={`${styles.fileRow} ${selected === f.name ? styles.fileRowActive : ''}`}
              onClick={() => { setSelected(f.name); onVideoOrientation?.(null); }}
              title={f.name}
            >
              {f.name}
            </button>
          ))}
        </div>

        <div className={styles.viewer}>
          {selected ? (
            <Viewer type={resourceType} name={selected} onOrientation={onVideoOrientation} />
          ) : (
            <div className={styles.empty}>
              <p className={styles.emptyText}>
                {visible.length > 0
                  ? 'Select a file to view it.'
                  : `Drop ${resourceType} files into your vault's _workspace/ folder.`}
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function Viewer({ type, name, onOrientation }) {
  const src = `/workspace/${encodeURIComponent(name)}`;

  if (type === 'video') {
    // Detect portrait (shorts) vs landscape from the real pixel size — no ffprobe
    // needed. Drives the Learn split orientation so tall videos get height.
    const onMeta = (e) => onOrientation?.(
      e.target.videoHeight > e.target.videoWidth ? 'portrait' : 'landscape');
    return <video key={src} className={styles.videoViewer} src={src} controls onLoadedMetadata={onMeta} />;
  }
  if (type === 'audio') {
    return (
      <div className={styles.audioViewer}>
        <p className={styles.audioName}>{name}</p>
        <audio key={src} src={src} controls className={styles.audioEl} />
      </div>
    );
  }
  if (type === 'pdf') {
    return <embed key={src} src={src} type="application/pdf" className={styles.pdfViewer} />;
  }
  return null;
}
