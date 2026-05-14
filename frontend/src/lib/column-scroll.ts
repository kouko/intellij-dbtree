/**
 * Geometry inputs for {@link computeScrollTopForCentering}.
 *
 * All values are pixel coordinates relative to the scrollable container:
 * - `firstTop`, `lastBottom` describe the bounding box of the highlighted
 *   span (first highlighted row's `offsetTop`, last highlighted row's
 *   `offsetTop + offsetHeight`).
 * - `clientHeight` is the visible height of the scroll viewport.
 * - `scrollHeight` is the total scrollable content height.
 */
export type ScrollCenteringInputs = {
  firstTop: number;
  lastBottom: number;
  clientHeight: number;
  scrollHeight: number;
};

/**
 * Compute the `scrollTop` value that centres a highlighted span inside
 * a scroll viewport. When the span is taller than the viewport, fall
 * back to scrolling the first highlighted row to the top of the viewport
 * (the user sees the start of the region rather than nothing).
 *
 * The returned value is always clamped to `[0, scrollHeight - clientHeight]`
 * so the caller can pass it to `element.scrollTo({ top, behavior: ... })`
 * without further bounds checks.
 */
export function computeScrollTopForCentering(
  inputs: ScrollCenteringInputs,
): number {
  const { firstTop, lastBottom, clientHeight, scrollHeight } = inputs;
  const maxScrollTop = Math.max(0, scrollHeight - clientHeight);

  const spanHeight = lastBottom - firstTop;
  if (spanHeight > clientHeight) {
    // Span doesn't fit — show the start of the highlighted region.
    return Math.max(0, Math.min(maxScrollTop, firstTop));
  }

  const midpoint = (firstTop + lastBottom) / 2;
  const desired = midpoint - clientHeight / 2;
  return Math.max(0, Math.min(maxScrollTop, desired));
}
