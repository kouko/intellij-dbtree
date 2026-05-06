import type { ModelLayer } from "../types";

export type ThemeName = "light" | "dark";

export interface LayerColors {
  border: string;
  bg: string;
  chip: string;
  text: string;
}

export interface Theme {
  name: ThemeName;
  background: string;
  panelBg: string;
  panelBorder: string;
  toolbarBg: string;
  toolbarText: string;
  toolbarTextMuted: string;
  toolbarTextSubtle: string;
  modelText: string;
  buttonBg: string;
  buttonBorder: string;
  buttonHoverBg: string;
  edge: string;
  edgeHighlight: string;
  selectedBorder: string;
  highlightBg: string;
  highlightText: string;
  codeBg: string;
  miniMapBg: string;
  miniMapMask: string;
  layers: Record<ModelLayer, LayerColors>;
}

const LIGHT: Theme = {
  name: "light",
  background: "#f8fafc",
  panelBg: "#ffffff",
  panelBorder: "#e2e8f0",
  toolbarBg: "#ffffff",
  toolbarText: "#0f172a",
  toolbarTextMuted: "#64748b",
  toolbarTextSubtle: "#94a3b8",
  modelText: "#0f172a",
  buttonBg: "#ffffff",
  buttonBorder: "#cbd5e1",
  buttonHoverBg: "#f1f5f9",
  edge: "#cbd5e1",
  edgeHighlight: "#f59e0b",
  selectedBorder: "#f59e0b",
  highlightBg: "rgba(245, 158, 11, 0.18)",
  highlightText: "#92400e",
  codeBg: "#fef3c7",
  miniMapBg: "#ffffff",
  miniMapMask: "rgba(241, 245, 249, 0.6)",
  layers: {
    source:       { border: "#94a3b8", bg: "#f1f5f9", chip: "#64748b", text: "#0f172a" },
    staging:      { border: "#60a5fa", bg: "#eff6ff", chip: "#2563eb", text: "#0f172a" },
    intermediate: { border: "#a78bfa", bg: "#f5f3ff", chip: "#7c3aed", text: "#0f172a" },
    marts:        { border: "#34d399", bg: "#ecfdf5", chip: "#059669", text: "#0f172a" },
  },
};

const DARK: Theme = {
  name: "dark",
  background: "#1e1f22",
  panelBg: "#2b2d30",
  panelBorder: "#393b40",
  toolbarBg: "#2b2d30",
  toolbarText: "#dfe1e5",
  toolbarTextMuted: "#a1a3a7",
  toolbarTextSubtle: "#6f7174",
  modelText: "#dfe1e5",
  buttonBg: "#3c3f41",
  buttonBorder: "#4e5256",
  buttonHoverBg: "#4a4d4f",
  edge: "#4e5256",
  edgeHighlight: "#fbbf24",
  selectedBorder: "#fbbf24",
  highlightBg: "rgba(251, 191, 36, 0.22)",
  highlightText: "#fde68a",
  codeBg: "#3a342a",
  miniMapBg: "#2b2d30",
  miniMapMask: "rgba(43, 45, 48, 0.7)",
  layers: {
    source:       { border: "#5d6573", bg: "#2f3236", chip: "#94a3b8", text: "#dfe1e5" },
    staging:      { border: "#3b82f6", bg: "#1f2a3d", chip: "#60a5fa", text: "#dfe1e5" },
    intermediate: { border: "#7c3aed", bg: "#251f3d", chip: "#a78bfa", text: "#dfe1e5" },
    marts:        { border: "#10b981", bg: "#1a2e29", chip: "#34d399", text: "#dfe1e5" },
  },
};

export const THEMES: Record<ThemeName, Theme> = { light: LIGHT, dark: DARK };

export function detectInitialTheme(): ThemeName {
  if (typeof window === "undefined") return "light";
  const fromHost = (window as unknown as { __DBTREE_THEME__?: ThemeName }).__DBTREE_THEME__;
  if (fromHost === "light" || fromHost === "dark") return fromHost;
  if (window.matchMedia?.("(prefers-color-scheme: dark)").matches) return "dark";
  return "light";
}
