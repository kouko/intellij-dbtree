import type { Theme } from "../lib/theme";

const HOP_STEPS = [0, 1, 2, 3, 5, 10] as const;
const UNLIMITED = Number.MAX_SAFE_INTEGER;

export function nextHop(value: number): number {
  if (value >= 10) return UNLIMITED;
  if (!Number.isFinite(value) || value > 10) return 10;
  const idx = HOP_STEPS.indexOf(value as (typeof HOP_STEPS)[number]);
  return HOP_STEPS[Math.min(idx + 1, HOP_STEPS.length - 1)];
}

export function prevHop(value: number): number {
  if (value === UNLIMITED) return 10;
  const idx = HOP_STEPS.indexOf(value as (typeof HOP_STEPS)[number]);
  return HOP_STEPS[Math.max(idx - 1, 0)];
}

export function isUnlimited(value: number): boolean {
  return value >= 1000;
}

function display(value: number): string {
  return isUnlimited(value) ? "∞" : String(value);
}

export interface HopStepperProps {
  label: string;
  value: number;
  onChange: (next: number) => void;
  theme: Theme;
}

export function HopStepper({ label, value, onChange, theme }: HopStepperProps) {
  const t = theme;
  const btnStyle: React.CSSProperties = {
    width: 22,
    height: 22,
    border: `1px solid ${t.buttonBorder}`,
    background: t.buttonBg,
    color: t.toolbarText,
    borderRadius: 4,
    cursor: "pointer",
    fontSize: 12,
    lineHeight: 1,
    padding: 0,
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
  };
  return (
    <div
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 4,
        color: t.toolbarTextMuted,
        fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
        fontSize: 12,
      }}
      title={`${label} hops (click steps through 0,1,2,3,5,10,∞)`}
    >
      <span aria-hidden style={{ minWidth: 14, textAlign: "center" }}>{label}</span>
      <button
        type="button"
        onClick={() => onChange(prevHop(value))}
        style={btnStyle}
        aria-label={`decrease ${label}`}
      >
        −
      </button>
      <span
        style={{
          minWidth: 22,
          textAlign: "center",
          color: t.toolbarText,
          fontVariantNumeric: "tabular-nums",
          fontWeight: 600,
        }}
      >
        {display(value)}
      </span>
      <button
        type="button"
        onClick={() => onChange(nextHop(value))}
        style={btnStyle}
        aria-label={`increase ${label}`}
      >
        +
      </button>
    </div>
  );
}
