import { describe, expect, it } from "vitest";
import { materializationLetter } from "./DbtModelNode";

/**
 * Locks the materialization → letter mapping. The wire format carries
 * whatever string dbt emits (we don't enumerate it on the Kotlin side),
 * so this map is the only place deciding which materializations get a
 * visible badge.
 */
describe("materializationLetter", () => {
  it("maps the three common iCHEF / dbt materializations to single letters", () => {
    expect(materializationLetter("table")).toBe("T");
    expect(materializationLetter("view")).toBe("V");
    expect(materializationLetter("incremental")).toBe("I");
  });

  it("supports the less common dbt materializations", () => {
    expect(materializationLetter("ephemeral")).toBe("E");
    expect(materializationLetter("materialized_view")).toBe("MV");
  });

  it("returns null for undefined (sources, unknown, missing)", () => {
    // Suppresses badge rendering — the alternative (some default letter)
    // would mislead the user about source nodes.
    expect(materializationLetter(undefined)).toBeNull();
  });

  it("returns null for unknown / custom-adapter materializations", () => {
    // Adapters can introduce their own materializations (e.g. Databricks
    // 'streaming_table'). We'd rather show no badge than guess a letter.
    expect(materializationLetter("streaming_table")).toBeNull();
    expect(materializationLetter("snapshot")).toBeNull();
    expect(materializationLetter("")).toBeNull();
  });
});
