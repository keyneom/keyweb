/** Icons are decorative only — every one of them travels with a word. */
const base = {
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  viewBox: "0 0 24 24",
  "aria-hidden": true,
};

export const KeyIcon = () => (
  <svg {...base} strokeWidth={1.8}>
    <circle cx="7.5" cy="7.5" r="4.5" />
    <path d="M10.8 10.8L21 21m-4-4l2.5-2.5M14 14l3 3" />
  </svg>
);

export const SearchIcon = () => (
  <svg {...base} strokeWidth={2.1}>
    <circle cx="10.5" cy="10.5" r="6.5" />
    <path d="M15.5 15.5L21 21" />
  </svg>
);

export const CopyIcon = () => (
  <svg {...base}>
    <rect x="8" y="8" width="12" height="12" rx="2.5" />
    <path d="M5 16H4.5A1.5 1.5 0 013 14.5v-10A1.5 1.5 0 014.5 3h10A1.5 1.5 0 0116 4.5V5" />
  </svg>
);

export const CheckIcon = () => (
  <svg {...base} strokeWidth={2.6}>
    <path d="M4 12.5l5.5 5.5L20 6.5" />
  </svg>
);

export const ShieldIcon = () => (
  <svg {...base} strokeWidth={1.9}>
    <path d="M12 3l7.5 3v6c0 4.5-3.1 8.2-7.5 9.5C7.6 20.2 4.5 16.5 4.5 12V6z" />
    <path d="M9 12l2.2 2.2L15.5 10" />
  </svg>
);

export const AlertIcon = () => (
  <svg {...base}>
    <path d="M12 3.5L21.5 20H2.5z" />
    <path d="M12 9.5v4.5M12 17.2v.1" />
  </svg>
);

export const ClockIcon = () => (
  <svg {...base}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 7.2V12l3.2 2" />
  </svg>
);

export const EyeIcon = () => (
  <svg {...base}>
    <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" />
    <circle cx="12" cy="12" r="2.8" />
  </svg>
);

export const BackIcon = () => (
  <svg {...base} strokeWidth={2.4}>
    <path d="M14.5 5L8 12l6.5 7" />
  </svg>
);

export const CloudIcon = () => (
  <svg {...base} strokeWidth={1.9}>
    <path d="M7 18.5a4.2 4.2 0 01-.4-8.4A5.6 5.6 0 0117.4 11a3.8 3.8 0 01-.4 7.5z" />
  </svg>
);

export const PlusIcon = () => (
  <svg {...base} strokeWidth={2.3}>
    <path d="M12 5v14M5 12h14" />
  </svg>
);
