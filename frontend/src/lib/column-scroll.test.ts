import { describe, expect, it } from "vitest";
import { computeScrollTopForCentering } from "./column-scroll";

/**
 * `computeScrollTopForCentering` is the pure math behind the column
 * list's auto-scroll. Given the geometry of the highlighted span and
 * the scroll viewport, it returns the `scrollTop` that centres the
 * span vertically — or, when the span is taller than the viewport,
 * falls back to scroll-to-top-of-first so the user at least sees the
 * start of the highlighted region.
 */
describe("computeScrollTopForCentering", () => {
  it("centres a small highlighted span inside the viewport", () => {
    // Highlight 100-150 inside a 0-285 viewport, scroll content height 600.
    // midpoint = 125, viewport half = 142.5 → scrollTop = 125 - 142.5 = -17.5
    // clamped to [0, scrollHeight - clientHeight] = [0, 315] → 0
    expect(
      computeScrollTopForCentering({
        firstTop: 100,
        lastBottom: 150,
        clientHeight: 285,
        scrollHeight: 600,
      }),
    ).toBe(0);
  });

  it("centres a span that sits in the middle of a long list", () => {
    // Highlight 400-450 inside 285-tall viewport, scrollHeight 1000.
    // midpoint = 425, viewport half = 142.5 → scrollTop = 282.5
    // clamped to [0, 715] → 282.5
    expect(
      computeScrollTopForCentering({
        firstTop: 400,
        lastBottom: 450,
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(282.5);
  });

  it("clamps to max scrollable when the span is near the bottom", () => {
    // Highlight 950-980 inside 285-tall viewport, scrollHeight 1000.
    // midpoint = 965, viewport half = 142.5 → scrollTop = 822.5
    // clamped to [0, scrollHeight - clientHeight] = [0, 715] → 715
    expect(
      computeScrollTopForCentering({
        firstTop: 950,
        lastBottom: 980,
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(715);
  });

  it("falls back to scroll-first-highlight-to-top when span is taller than viewport", () => {
    // Highlight 100-500 (span 400) inside 285-tall viewport.
    // Span > viewport → return firstTop so the user sees the start.
    expect(
      computeScrollTopForCentering({
        firstTop: 100,
        lastBottom: 500,
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(100);
  });

  it("clamps fallback to max scrollable when first highlight is near the bottom", () => {
    // Even in fallback, scrollTop must respect [0, scrollHeight - clientHeight].
    expect(
      computeScrollTopForCentering({
        firstTop: 950,
        lastBottom: 1500, // tall span
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(715);
  });

  it("returns 0 when the entire content fits inside the viewport", () => {
    // scrollHeight <= clientHeight → no scrolling possible.
    expect(
      computeScrollTopForCentering({
        firstTop: 50,
        lastBottom: 80,
        clientHeight: 285,
        scrollHeight: 285,
      }),
    ).toBe(0);
  });
});
