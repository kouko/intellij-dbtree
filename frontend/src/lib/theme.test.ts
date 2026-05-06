import { afterEach, describe, expect, it, vi } from "vitest";
import { detectInitialTheme } from "./theme";

/**
 * Priority of theme sources, in order:
 *   1. window.__DBTREE_THEME__ (host-injected — JetBrains pushes its current LAF)
 *   2. window.matchMedia("(prefers-color-scheme: dark)") (standalone browser)
 *   3. fallback to "light"
 *
 * detectInitialTheme reads `window` at call time, so each test patches and
 * restores the global. vitest's vi.stubGlobal handles the cleanup.
 */
describe("detectInitialTheme", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses host-injected dark over media query", () => {
    vi.stubGlobal("window", {
      __DBTREE_THEME__: "dark",
      matchMedia: () => ({ matches: false }),
    });
    expect(detectInitialTheme()).toBe("dark");
  });

  it("uses host-injected light over media query that prefers dark", () => {
    vi.stubGlobal("window", {
      __DBTREE_THEME__: "light",
      matchMedia: () => ({ matches: true }),
    });
    expect(detectInitialTheme()).toBe("light");
  });

  it("ignores host injection that is not 'light' or 'dark' (defensive)", () => {
    vi.stubGlobal("window", {
      __DBTREE_THEME__: "high-contrast",
      matchMedia: () => ({ matches: true }),
    });
    expect(detectInitialTheme()).toBe("dark"); // falls through to media query
  });

  it("falls back to media query when no host injection", () => {
    vi.stubGlobal("window", {
      matchMedia: () => ({ matches: true }),
    });
    expect(detectInitialTheme()).toBe("dark");
  });

  it("falls back to light when media query says light", () => {
    vi.stubGlobal("window", {
      matchMedia: () => ({ matches: false }),
    });
    expect(detectInitialTheme()).toBe("light");
  });

  it("falls back to light when matchMedia is unavailable", () => {
    vi.stubGlobal("window", {});
    expect(detectInitialTheme()).toBe("light");
  });

  it("falls back to light in non-browser contexts (no window)", () => {
    // SSR-style guard — also exercised the first time the module loads
    // before the JCEF page is ready.
    vi.stubGlobal("window", undefined);
    expect(detectInitialTheme()).toBe("light");
  });
});
