export default function Ring({ pct, size = 44, strokeWidth = 4 }) {
  const r = (size / 2) - (strokeWidth * 1.5);
  const circumference = 2 * Math.PI * r;
  const offset = circumference * (1 - pct / 100);
  const center = size / 2;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ flexShrink: 0 }}>
      <circle
        cx={center} cy={center} r={r}
        fill="none"
        stroke="var(--color-border)"
        strokeWidth={strokeWidth}
      />
      <circle
        cx={center} cy={center} r={r}
        fill="none"
        stroke="var(--color-accent)"
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeDasharray={circumference}
        strokeDashoffset={offset}
        transform={`rotate(-90 ${center} ${center})`}
      />
      <text
        x={center} y={center + 4}
        textAnchor="middle"
        fill="var(--color-text)"
        fontSize="12"
        fontFamily="var(--font-mono)"
      >
        {pct}%
      </text>
    </svg>
  );
}
