# Changelog

## 1.4.1 — 2026-11-16

**Fixed: every cart-to-cart coupling in a train was erased a few seconds after a world load — permanently**

Symptom: after loading a save (or otherwise reloading the carts), the whole train came apart and the log filled with `[cart-link] unlinked` lines. There were **zero** `coupler snapped` lines, so physics did not break them. Worse, it is permanent: `unlinkPair` calls `CoasterCartTrainLinkData.removeLink`, which deletes the record from the cart's persisted NBT, so a restart does not bring it back.

Evidence (full log sweep on the reporting machine — `unlinked` / `restored link`):

| Version | Session | unlinked | restored |
|---|---|---|---|
| 1.0.1 | 11-15 ×6 | 0 | 0 |
| **1.2.0** | 11-16 19:43 | **11** (one burst 6 s after load) | 0 |
| 1.3.0 | 11-16 | 108 (with 33 restores, 78 new links, 6 snaps) | 33 |
| 1.4.0 | 11-16 daytime | 1 (80 links created that session) | 0 |
| 1.4.0 | 11-16 evening | **78 (one burst on load)** | **0** |

`unlinked` first appears **after 1.1.0 added the scan TTL**, and every burst is a few seconds after a load; the six 1.0.1 sessions have none at all.

The chain: `restorePersistedLinks` → `pruneDefunctLinks` / `tryRestoreLink` → `isDefunctLinkPartner(level, partnerId)` → `CoasterCartResolve.isCartSubLevel` → `CoasterCartBearingTickCache.scan`. Upstream re-scans that on **every tick**; 1.1.0 replaced it with a 40 / 100-tick TTL. During a world load the sub-level is already in the container while its plot is not populated yet (or the cache still holds the previous "no bearing cell" answer), so `isCartSubLevel` returns `false` for a cart that is merely **not observable yet**. Upstream reads that `false` as "this cart is gone" and deletes the coupling.

Upstream gets away with it because the next tick rescans and the answer recovers immediately; the TTL stretched "not observable" into 2–5 seconds, which is long enough to delete the entire train first.

Fix (two independent layers, either one breaks the chain):

1. **Re-adjudicate before destroying** (new `CartLinkGuardMixin`, hooked into `isDefunctLinkPartner` and `onCartRemoved`): before anything is deleted, `CartMembership` recomputes membership **bypassing the cache**. A verdict of `CART` (the fresh scan finds the bearing cell) or `UNKNOWN` (the plot holds no block at all — the data is not here yet) **refuses the deletion**; only "the plot is populated and genuinely holds no bearing cell" unlinks, exactly as upstream intends. Genuinely removed carts (`isRemoved()`) are unaffected and still cleaned up.
2. **Negative answers are not cached** when the plot has no chunk object at all — that is precisely the load window, and caching it writes "not loaded yet" down as "does not exist".

Cost: the guard only runs on paths that were **about to delete something**, and the `onCartRemoved` side short-circuits on a single NBT key, so sub-levels without couplings are never rescanned. The hot path is untouched. Switch `protectCartLinks` (on by default).

- A refusal is logged so the next investigation starts with facts:
  `[simcoastersfix] kept cart-train couplings of <uuid> at isDefunctLinkPartner (...)`.
- Self-check 11 → **12** items.

**Known limitations**

- **Couplings already erased by an earlier load cannot be recovered** — they are gone from the NBT. Roll back to a world backup taken before the incident, or re-link the carts by hand; this build only stops it from happening again.
- `pruneInvalidLinksForCoupling` (the branch that runs when a player links a cart by hand) still reads the cache and has no guard. It only runs on player interaction, so the exposure is far smaller than the per-tick `restorePersistedLinks` sweep.

## 1.4.0 — 2026-11-16

**New: bezier curve handle-length cache — the real reason a cart on a track is much more expensive than one off it**

Measured: with carts snapped to a track, the single biggest item on the server tick thread is **not** physics but repeated curve handle-length resolution (`Rapier3D.step` was 12–18% in the same windows; the curve group reached 30%).

- `CoasterPeerCurveHandleLengths.getLengthAtEndpoint` is called from the curve sampling loop, and it **unconditionally** computes `fallbackSingleHandleLength` → `BezierConnection.getHandleLength()`, which upstream itself wraps with a mixin that re-enters `getCanonicalLengths`. One endpoint query therefore costs two canonical lookups, and every sample needs both endpoints — about four per sample point.
- Each `getCanonicalLengths` costs three map operations: two `synchronizedMap(WeakHashMap)` gets plus a freshly built `"<long>:<long>"` string key into `BY_EDGE`.
- The result is now cached per connection and endpoint, invalidated by upstream's own `AtomicLong REVISION` (every writer bumps it), so **no time-based staleness is introduced**. Switch `curveHandleLengthCache` (on by default).
- **A/B measurement** (same session, hot config reload, carts running on the track):

| | busy samples | curve samples | of which canonical | MSPT p95 | p99 |
|---|---|---|---|---|---|
| off (= upstream) | 1141 / 1137 | 249 / 351 | **219 / 328** | 14.12 / 14.63 | 15.99 / 16.90 |
| on | 876 / 715 | 84 / 93 | **0 / 0** | 13.60 / 11.76 | 15.46 / 13.42 |

  The residual 84/93 is the cache hit itself; not a single upstream lookup sample remains.
- New compile-time dependency `libs/create.jar` (only for `BezierConnection`, **not shipped**); self-check 10 → **11** items.

**Known limitation.** Invalidation is exact through the revision counter — a track edit is visible on the very next call, with no perceivable delay.

## 1.3.0 — 2026-11-16

**New: de-duplicated physics-pipeline reads in the balloon path**

`BalloonTetherController.onPrePhysicsTick` reads the same velocities over and over: `applyTetherSpring` reads four (balloon linear/angular, host linear/angular), `dampHostSwing` reads the host again, and `dampHostVerticalInBand` reads it once more. Each read is a `PhysicsPipeline` round trip into Rapier. In the patched bytecode that is **7 call sites covered by 5 wrappers**.

- Forces and impulses are only *queued* during a pre-physics hook and applied at the step, so a body's velocity cannot change between two reads in the same invocation. It is memoised per body, switch `balloonPhysicsMemo` (on by default).
- The memo fills the caller's buffer with a **copy**; aliasing a shared vector would be a physics bug. Only the round trip is saved.
- Deliberately untouched: the duplicate `wakeUp` calls (no evidence they matter, and every extra injector is another way an upstream rename can break the load), the two `level.clip()` hover raycasts (they feed the hover feel), and the ~20 vector/quaternion allocations per balloon (would need a method-body rewrite).
- The startup self-check grew from 9 to **10** items.

**Known limitation.** The memo lives for exactly one hook invocation and never caches across ticks. Turning the switch off restores the upstream read pattern (the behaviour is identical either way; the switch is there for A/B and troubleshooting).

## 1.2.0 — 2026-11-16

**New: the per-tick duplicate work around balloons and train links**

Upstream treats a value that changes every tick (`gameTime`) as a once-only flag, and makes the same mistake twice. The train-link one was already throttled in 1.0.0; this release covers the balloon one and removes two cases of asking the same question twice inside a single tick.

- **Balloon restore scan throttled.** `BalloonTetherController.maybeRestoreTethers` guards itself with `last != gameTime`, which is true on every tick, so `restoreTethers` walks **every sub-level** on **every physics tick** (one `getBlockState(ZERO)` and one `containsKey` each) to re-register balloons that are already registered. Switch `balloonRestoreThrottle` (on by default), interval `balloonRestoreIntervalTicks` (40 ticks).
- **Balloon lookups memoised within a tick.** `onPrePhysicsTick` walks the active set twice and resolves balloon → sub-level → block entity → host in both passes. The second resolution inside one invocation now reuses the first. Switch `balloonTickMemo` (on by default).
- **Train-link cart lookups memoised within a tick.** `tick` resolves cart A and cart B per active link; the duplicate inside one tick is dropped. Switch `trainLinkTickMemo` (on by default).
- The startup self-check grew from 6 to **9** items, and the startup log prints the state of all three switches.

**Unchanged.** All three memos live for exactly one hook invocation and never cache across ticks. The `List.copyOf` snapshot in `ACTIVE_LINKS` stays — it is what makes unlinking during the loop safe. The two `level.clip()` hover raycasts and the ~20 `Vector3d`/`Quaterniond` allocations per balloon are untouched: they feed the physics feedback loop and need in-game validation, not a blind patch.

**Known limitation.** Balloon restore visibility delay equals `balloonRestoreIntervalTicks` (40 ticks / 2 s), and only balloons that never went through the sub-level observer can hit it. Turning any switch off restores the upstream behaviour exactly.

## 1.1.0 — 2026-11-16

**New: section-level pruning inside the scan primitive**

1.0.1 reduced *how often* the scans run (four TTLs / throttles). The cost of a single scan was untouched. This release attacks that cost.

- `CoasterCartPlotScan.scanBearingCells` / `forEachBearingWorldCell` used `section.hasOnlyAir()` as their only prune, but that is just `nonEmptyBlockCount == 0` — it rejects **all-air** sections only. A section holding a single block (rail, stone, wool) still walked all 4096 `getBlockState` calls. The same guard now also asks `LevelChunkSection.maybeHas(state -> state.is(wantedBlock))`, so a section whose palette holds no such block is skipped outright.
- **Behaviour is unchanged.** `representative` (the `compareYXZ` minimum), `bearingCount` and the contents and order of `cells` are all identical — a skipped section was going to contribute zero matches. `GlobalPalette` (>256 distinct states) returns `true` unconditionally, so those sections fall back to the original walk; a `false` result is a proof of absence, never a guess.
- The win reaches further than the TTL did: `RivetDisassemblyEnforcer` calls `scanBearingCells` directly, and the client-side `forEachBearingWorldCell` fallback plus `CoasterCartDebugRenderer` hit the same pair of methods.
- The startup self-check grew from 4 to 6 items: `RivetDisassemblyEnforcer#simc$lastSelfHostCheck` (injected since 1.0.1 but never verified) and the new `CoasterCartPlotScan#simc$sectionsSkipped`. Log lines now read `Class#field` so a class with two checked members no longer looks like a duplicated line.

**Unchanged:** the defaults of all four throttle intervals, the config format, the network protocol, and the blueprint compatibility layer. Clients still do not need this mod.

**Known limitation:** the TTL visibility delay (40 / 100 ticks) is unchanged — this release lowers per-scan cost, it does not shorten the delay. Sections with more than 256 distinct block states keep the original cost.

Next: take the event-driven index (`plot → Set<BlockPos>`, maintained incrementally so a scan collapses into a lookup) upstream as an issue + PR — see [`OPTIMIZATION.md`](OPTIMIZATION.md) §5 D.

## 1.0.1 — 2026-11-15

**New: optional Sable Photomancy blueprint compatibility**

Enabled automatically when `sable_schematic_api 1.0.0+` is installed. Without it nothing changes and the performance throttles behave exactly as in 1.0.0.

- **Rivets are no longer torn down after a paste.** A rivet remembered where it was pinned as an absolute block position, so a pasted rivet still pointed at the coordinates of the original build; Coasters concluded its host was gone and removed it. The host is now stored as a blueprint-relative block reference — or as an offset from the blueprint origin when the host is a plain world block — and resolved back to the new coordinates *before* the block entity loads.
- **Red balloons re-attach to the pasted host sub-level.** They stored the raw sub-level uuid, which a paste always replaces, so the host could never be resolved again.
- **Cart train couplings survive.** Couplings live in the sub-level's user data, which Photomancy never exported. They now travel in a blueprint sidecar, with both endpoint uuids remapped.
- Every key added to the save format is namespaced `simcoastersfix:`, and **blueprints saved with 1.0.0 still load** — they simply do not carry these fixes.

**Unchanged:** the four performance throttles, the config file, the network protocol, and every block, item and recipe. Clients still do not need this mod.

**Known limitation:** track anchorpoint curve links (`AnchorPeerCurves`) are still stored as absolute coordinates and are not handled yet.

Mechanism and the log lines to verify with: [`BLUEPRINT_COMPAT.md`](BLUEPRINT_COMPAT.md).

## 1.0.0 — 2026-11-14

Initial release. Four "re-run in full every tick" paths inside Sable's pre-physics tick became configurable throttles: bearing plot scans, rivet disassembly checks, cart teardown broadcasts, and persisted train-link restores.
