# Milestone 3 — Deferred Performance Work

These items were designed during the fork-review optimisation pass
(branch `claude/fork-review-optimize-9oYIl`) but require a compilable build
environment (Java 25 + maven.wagyourtail.xyz access) before they can be
safely implemented and tested.

---

## 1. PathNode Object Pool

### Problem
`AStarPathFinder` allocates a new `PathNode` for every previously-unseen
position it explores. A typical search explores 25 000 – 80 000 nodes, so
GC pressure from short-lived node objects is non-trivial. The existing
`Long2ObjectOpenHashMap<PathNode>` in `AbstractNodeCostSearch` already
prevents *duplicate* nodes, but the first visit always allocates.

### Proposed Design

```java
// AbstractNodeCostSearch — new field
private final ArrayDeque<PathNode> nodePool = new ArrayDeque<>(8192);

// Replace the current new-node branch in getNodeAtPosition():
if (node == null) {
    node = nodePool.isEmpty() ? new PathNode(x, y, z, goal)
                              : nodePool.poll().reset(x, y, z, goal);
    map.put(hashCode, node);
}
```

`PathNode.reset(int x, int y, int z, Goal goal)` needs to be added:

```java
public PathNode reset(int x, int y, int z, Goal goal) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.cost = Double.MAX_VALUE;
    this.combinedCost = Double.MAX_VALUE;
    this.previous = null;
    this.isOpen = false;
    this.heapPosition = -1;
    this.estimatedCostToGoal = goal.heuristic(x, y, z);
    return this;
}
```

After each search completes (in the `finally` block of
`AbstractNodeCostSearch.calculate()`), drain the map values back into the
pool — but only if the pool is being reused across searches (requires
making the pool a field of an outer object that survives multiple
`AStarPathFinder` instantiations, e.g. `PathingBehavior`).

> **Warning:** `PathNode` fields `isOpen` and `heapPosition` are mutated by
> `BinaryHeapOpenSet`. The `reset()` method **must** clear both before
> the node is handed to the heap. Any field added to `PathNode` in the
> future must also be cleared in `reset()`.

### Expected Gain
Reduced allocation rate ~= one `PathNode` per explored node per search → GC
minor-pause frequency drops noticeably in long sessions.

---

## 2. Jump Point Search (JPS) — Why It Was Rejected

JPS achieves O(1) node expansion on uniform-cost grids by symmetry-breaking:
it skips straight runs and only records "jump points" where the optimal
path must turn. This cuts explored nodes by 10–40× on open terrain.

**Baritone is not a uniform-cost grid.** Every movement type has a
different cost:

| Movement type          | Cost model                         |
|------------------------|------------------------------------|
| Walk / sprint          | ~3.564 per block                   |
| Diagonal walk          | ~3.564 × √2 ≈ 5.04                 |
| Jump up 1 block        | JUMP_ONE_BLOCK_COST ≈ 2×walk       |
| Fall (n blocks)        | FALL_N_BLOCKS_COST[n] (lookup table)|
| Break block            | +ticksToBreak × 20                  |
| Place block (bridging) | +PLACE_ONE_BLOCK_COST               |
| Water traversal        | WALK_ONE_IN_WATER_COST             |
| Parkour / leap         | custom cost                        |

JPS's correctness proof depends on the *symmetric* property: any two
paths of equal length between the same endpoints have equal cost. That
property is violated whenever different movement types are available.
Retrofitting JPS for non-uniform costs degrades it to "Bounded JPS" which
requires re-checking every skipped node's cost — negating most of the gain.

**Verdict:** do not implement JPS. If faster node expansion is required,
the correct approach is **hierarchical pathfinding** (pre-computed
chunk-level graph + local A*), which is a much larger project.

---

## 3. Line-of-Sight Gating for Mob Avoidance (medium effort)

Currently `Avoidance.coefficient(x, y, z)` applies the full avoidance cost
to every node within `radius` of a mob, even if there is solid rock between
the node and the mob. This causes paths through tunnels to be incorrectly
penalised when a hostile mob is on the other side of a wall.

### Proposed Design

In `Avoidance.coefficient(int x, int y, int z)`:

```java
// After distance check but before applying coefficient:
if (bsi != null && !hasLineOfSight(bsi, x, y, z)) {
    return 1.0; // no penalty through solid blocks
}
```

```java
private boolean hasLineOfSight(BlockStateInterface bsi, int x, int y, int z) {
    // Step the integer Bresenham ray from mob position to node position.
    // If any non-passable block is encountered, return false.
    // Re-use the same BSI that AStarPathFinder already passes around;
    // no extra world access needed.
    ...
}
```

`Avoidance` would need to store the mob's block position (already present as
`pos`) and accept a `BlockStateInterface` reference. The `BlockStateInterface`
is available in `CalculationContext` and can be threaded through
`Favoring.calculate()` without API breakage.

> **Note:** This does *not* require compilation to design, but does require
> it to validate the Bresenham ray against the BSI API surface and to
> benchmark the per-node ray cost against the avoidance-off baseline.

---

*Document authored during fork-review optimisation pass, 2026-05-23.*
