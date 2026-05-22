// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, fireEvent, cleanup } from "@testing-library/react";
import type { ReactNode } from "react";
import { DbtModelNode, type DbtModelNodeData } from "./DbtModelNode";
import { THEMES } from "../lib/theme";

// React Flow's NodeToolbar / Handle read context from a host <ReactFlow>
// store that isn't present in this unit test. Mocking the surface we use
// keeps the test focused on DbtModelNode's own contract — "when the card
// is hovered, the tooltip is visible and shows data.name" — without
// having to spin up a full <ReactFlowProvider> + store.
vi.mock("@xyflow/react", () => ({
  // Render the toolbar as a sentinel <div> with the visibility flag
  // surfaced as data-visible. The test asserts on this attribute.
  NodeToolbar: ({
    isVisible,
    children,
  }: {
    isVisible?: boolean;
    children?: ReactNode;
  }) => (
    <div data-testid="node-toolbar" data-visible={isVisible ? "true" : "false"}>
      {children}
    </div>
  ),
  // Handles are irrelevant to this test; render as empty markers.
  Handle: () => <div data-testid="handle" />,
  Position: { Top: "top", Right: "right", Bottom: "bottom", Left: "left" },
}));

function buildData(overrides: Partial<DbtModelNodeData> = {}): DbtModelNodeData {
  return {
    unique_id: "model.demo.fct_orders",
    name: "fct_orders",
    package_name: "demo",
    layer: "marts",
    materialization: "table",
    columns: [],
    expanded: false,
    highlightedColumns: new Set<string>(),
    onLineagePath: false,
    isSelectedModel: false,
    theme: THEMES.light,
    cardWidth: 240,
    onToggleExpanded: vi.fn(),
    onColumnClick: vi.fn(),
    onOpenFile: vi.fn(),
    ...overrides,
  };
}

describe("DbtModelNode hover tooltip", () => {
  beforeEach(() => {
    // Quiet React 19's act() warning surface for these tiny synchronous
    // state changes — fireEvent already wraps internally.
  });
  afterEach(() => {
    cleanup();
  });

  it("hides the NodeToolbar before hover", () => {
    const data = buildData();
    // @ts-expect-error — NodeProps requires more fields than the
    // component actually reads. Passing just { data } is enough for the
    // logic under test (the body only destructures `data`).
    const { getByTestId } = render(<DbtModelNode data={data} />);
    expect(getByTestId("node-toolbar").getAttribute("data-visible")).toBe("false");
  });

  it("shows the model name inside the NodeToolbar on mouseEnter", () => {
    const data = buildData({ name: "extremely_long_intermediate_table_for_xyz" });
    // @ts-expect-error — see above note on minimal NodeProps shape.
    const { getByTestId, container } = render(<DbtModelNode data={data} />);

    // Sanity: invisible at mount.
    expect(getByTestId("node-toolbar").getAttribute("data-visible")).toBe("false");

    // Trigger hover on the outer card. The first <div> child of the
    // render root is the card root that owns the onMouseEnter handler.
    const card = container.firstChild as HTMLElement;
    fireEvent.mouseEnter(card);

    const toolbar = getByTestId("node-toolbar");
    expect(toolbar.getAttribute("data-visible")).toBe("true");
    expect(toolbar.textContent).toBe("extremely_long_intermediate_table_for_xyz");
  });

  it("hides the NodeToolbar again on mouseLeave", () => {
    const data = buildData();
    // @ts-expect-error — see above note on minimal NodeProps shape.
    const { getByTestId, container } = render(<DbtModelNode data={data} />);
    const card = container.firstChild as HTMLElement;

    fireEvent.mouseEnter(card);
    expect(getByTestId("node-toolbar").getAttribute("data-visible")).toBe("true");

    fireEvent.mouseLeave(card);
    expect(getByTestId("node-toolbar").getAttribute("data-visible")).toBe("false");
  });
});
