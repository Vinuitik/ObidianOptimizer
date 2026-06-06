const facets = [
  { clip: 'polygon(50% 0, 0 36%, 50% 44%)',      bg: 'linear-gradient(135deg, #5a4d8f 0%, #2c2547 100%)' },
  { clip: 'polygon(50% 0, 100% 36%, 50% 44%)',    bg: 'linear-gradient(225deg, #221d36 0%, #100d1c 100%)' },
  { clip: 'polygon(0 36%, 50% 44%, 24% 100%)',    bg: 'linear-gradient(160deg, #2a2440 0%, #0c0a16 100%)' },
  { clip: 'polygon(100% 36%, 50% 44%, 76% 100%)', bg: 'linear-gradient(200deg, #151221 0%, #060509 100%)' },
  { clip: 'polygon(50% 44%, 24% 100%, 76% 100%)', bg: 'linear-gradient(180deg, #1c1830 0%, #08070e 100%)' },
];

export default function ObsidianMark({ size = 120, glow = true }) {
  const w = size;
  const h = size * 1.32;
  return (
    <div style={{
      width: w,
      height: h,
      position: 'relative',
      flexShrink: 0,
      filter: glow ? 'drop-shadow(0 8px 26px rgba(124,92,255,0.45))' : 'none',
    }}>
      {facets.map((f, i) => (
        <div key={i} style={{ position: 'absolute', inset: 0, clipPath: f.clip, background: f.bg }} />
      ))}
      {/* specular edge */}
      <div style={{
        position: 'absolute', inset: 0,
        clipPath: 'polygon(50% 0, 0 36%, 4% 38%, 50% 6%)',
        background: 'linear-gradient(135deg, #c8b8ff 0%, #7c5cff 100%)',
        opacity: 0.95,
      }} />
      {/* central ridge gloss */}
      <div style={{
        position: 'absolute', inset: 0,
        clipPath: 'polygon(50% 6%, 47% 44%, 50% 98%, 53% 44%)',
        background: 'linear-gradient(180deg, rgba(200,184,255,0.55), rgba(124,92,255,0.05) 60%, transparent)',
        opacity: 0.7,
      }} />
    </div>
  );
}
