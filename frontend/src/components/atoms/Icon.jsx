const paths = {
  folder:   <path d="M3 6.5A1.5 1.5 0 0 1 4.5 5h4l2 2.5h7A1.5 1.5 0 0 1 19 9v8.5A1.5 1.5 0 0 1 17.5 19h-13A1.5 1.5 0 0 1 3 17.5z" />,
  file:     <><path d="M6 3.5h7l5 5V20a.5.5 0 0 1-.5.5h-11A.5.5 0 0 1 6 20z" /><path d="M13 3.5V8.5h5" /></>,
  chevron:  <path d="M9 6l6 6-6 6" />,
  search:   <><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4 4" /></>,
  plus:     <><path d="M12 5v14" /><path d="M5 12h14" /></>,
  sparkle:  <path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z" />,
  clock:    <><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>,
  flame:    <path d="M12 3c1 3-2 4-2 7a2 2 0 0 0 4 0c0 0 2 2 2 5a6 6 0 1 1-9-5c2-2 3-4 2-7 1 .5 2 .5 3 0z" />,
  dot:      <circle cx="12" cy="12" r="3.5" />,
  check:    <path d="M5 12.5l4.5 4.5L19 7" />,
  link:     <><path d="M10 14a4 4 0 0 0 5.66 0l2.5-2.5a4 4 0 0 0-5.66-5.66L11 7.34" /><path d="M14 10a4 4 0 0 0-5.66 0l-2.5 2.5a4 4 0 0 0 5.66 5.66L13 16.66" /></>,
  settings: <><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2" /></>,
  refresh:  <><path d="M20 11A8 8 0 0 0 6.3 6.3L4 8.6" /><path d="M4 4v4.6h4.6" /><path d="M4 13a8 8 0 0 0 13.7 4.7L20 15.4" /><path d="M20 20v-4.6h-4.6" /></>,
};

export default function Icon({ name, size = 16, color = 'currentColor', strokeWidth = 1.6 }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ flexShrink: 0, display: 'block' }}
    >
      {paths[name]}
    </svg>
  );
}
