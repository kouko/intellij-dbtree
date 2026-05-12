import { layoutModelGraph as layoutDagre } from "./layout-dagre";
import { layoutModelGraph as layoutElk } from "./layout-elk";

export type { LayoutOptions } from "./layout-dagre";
export type { EdgeRoute, LayoutResult } from "./layout-elk";

const ENGINE = "elk" as "dagre" | "elk";

export const layoutModelGraph = ENGINE === "elk" ? layoutElk : layoutDagre;
