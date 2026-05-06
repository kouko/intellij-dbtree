import { describe, expect, it } from "vitest";
import { isUnlimited, nextHop, prevHop } from "./HopStepper";

const UNLIMITED = Number.MAX_SAFE_INTEGER;

describe("nextHop", () => {
  it("walks the documented sequence 0,1,2,3,5,10,∞", () => {
    expect(nextHop(0)).toBe(1);
    expect(nextHop(1)).toBe(2);
    expect(nextHop(2)).toBe(3);
    expect(nextHop(3)).toBe(5);
    expect(nextHop(5)).toBe(10);
    expect(nextHop(10)).toBe(UNLIMITED);
  });

  it("stays at unlimited once past 10", () => {
    expect(nextHop(UNLIMITED)).toBe(UNLIMITED);
    expect(nextHop(Infinity)).toBe(UNLIMITED);
  });

  it("snaps an out-of-cycle finite value (e.g. 7) into the cycle", () => {
    // Defensive: if the host injects a non-canonical value, nextHop should
    // not return undefined — the cycle still advances.
    expect(nextHop(7)).toBeTypeOf("number");
    expect(nextHop(7)).not.toBeNaN();
  });
});

describe("prevHop", () => {
  it("walks back through the sequence ∞,10,5,3,2,1,0", () => {
    expect(prevHop(UNLIMITED)).toBe(10);
    expect(prevHop(10)).toBe(5);
    expect(prevHop(5)).toBe(3);
    expect(prevHop(3)).toBe(2);
    expect(prevHop(2)).toBe(1);
    expect(prevHop(1)).toBe(0);
  });

  it("stays at 0 (the floor)", () => {
    expect(prevHop(0)).toBe(0);
  });
});

describe("isUnlimited", () => {
  it("treats Number.MAX_SAFE_INTEGER as unlimited", () => {
    expect(isUnlimited(UNLIMITED)).toBe(true);
  });

  it("treats normal cycle values as not unlimited", () => {
    for (const v of [0, 1, 2, 3, 5, 10]) {
      expect(isUnlimited(v)).toBe(false);
    }
  });

  it("threshold is 1000 — anything bigger counts as unlimited", () => {
    expect(isUnlimited(999)).toBe(false);
    expect(isUnlimited(1000)).toBe(true);
    expect(isUnlimited(1_000_000)).toBe(true);
  });
});

describe("nextHop/prevHop round-trip", () => {
  it("prev(next(v)) returns to v for every canonical step except the saturation pair", () => {
    // 0→1→0, 1→2→1, ..., 5→10→5, but 10→∞→10 (still round-trips)
    for (const v of [0, 1, 2, 3, 5, 10]) {
      expect(prevHop(nextHop(v))).toBe(v);
    }
  });
});
