import { useEffect, useRef } from "react";
import { useReactFlow } from "@xyflow/react";

const PAN_STEP_PX = 40;
const RESET_ZOOM = 1;
const ZOOM_DURATION_MS = 100;
const FIT_DURATION_MS = 200;

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
}

export function KeyboardShortcuts() {
  const { zoomIn, zoomOut, zoomTo, fitView, setViewport, getViewport } =
    useReactFlow();

  // Tracks codes whose keydown we already acted on, so the keyup
  // fallback doesn't double-fire under non-IME conditions.
  const handledOnKeydownRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    const panBy = (dx: number, dy: number) => {
      const vp = getViewport();
      setViewport({ x: vp.x + dx, y: vp.y + dy, zoom: vp.zoom });
    };

    // Returns true if the physical key code triggered an action.
    const runShortcut = (code: string): boolean => {
      switch (code) {
        case "KeyF":
          fitView({ duration: FIT_DURATION_MS });
          return true;
        case "Equal":
        case "NumpadAdd":
          zoomIn({ duration: ZOOM_DURATION_MS });
          return true;
        case "Minus":
        case "NumpadSubtract":
          zoomOut({ duration: ZOOM_DURATION_MS });
          return true;
        case "Digit0":
        case "Numpad0":
          zoomTo(RESET_ZOOM, { duration: FIT_DURATION_MS });
          return true;
        // Arrow keys move the camera in the arrow direction
        // (viewport offset shifts the opposite way to expose that side).
        case "ArrowUp":
          panBy(0, PAN_STEP_PX);
          return true;
        case "ArrowDown":
          panBy(0, -PAN_STEP_PX);
          return true;
        case "ArrowLeft":
          panBy(PAN_STEP_PX, 0);
          return true;
        case "ArrowRight":
          panBy(-PAN_STEP_PX, 0);
          return true;
        default:
          return false;
      }
    };

    const shouldSkip = (event: KeyboardEvent): boolean => {
      if (event.metaKey || event.ctrlKey || event.altKey) return true;
      if (isTypingTarget(event.target)) return true;
      return false;
    };

    const onKeyDown = (event: KeyboardEvent) => {
      if (shouldSkip(event)) return;
      if (runShortcut(event.code)) {
        handledOnKeydownRef.current.add(event.code);
        event.preventDefault();
      }
    };

    // macOS IME (注音 etc.) on JCEF swallows keydown but lets keyup
    // through. Re-run the shortcut on keyup when keydown didn't fire,
    // so the same bindings keep working with IME on.
    const onKeyUp = (event: KeyboardEvent) => {
      if (handledOnKeydownRef.current.delete(event.code)) return;
      if (shouldSkip(event)) return;
      if (runShortcut(event.code)) {
        event.preventDefault();
      }
    };

    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("keyup", onKeyUp);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("keyup", onKeyUp);
    };
  }, [zoomIn, zoomOut, zoomTo, fitView, setViewport, getViewport]);

  return null;
}
