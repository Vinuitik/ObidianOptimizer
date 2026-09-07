// Shared with anywhere that shows Drive-sync progress (SyncPage's own readout, the
// persistent MobileLayout banner) so the wording never drifts between the two.
const STAGE_LABELS = {
  notes: 'notes', cards: 'flashcards', inbox: 'inbox',
  images: 'images', media: 'video & audio', pdf: 'PDF pages',
};

// `s` is the { stage, done, total } the various onStage callbacks report — null while idle.
export function stageText(s) {
  if (!s) return 'Downloading…';
  const label = STAGE_LABELS[s.stage] || s.stage;
  return `Downloading ${label}${s.total ? ` ${s.done}/${s.total}` : ''}…`;
}
