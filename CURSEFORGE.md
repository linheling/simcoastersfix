# Coaster Perf Fix (Unofficial)

**Unofficial server-side performance patch for [Create: Coasters Simulated](https://modrinth.com/mod/create-coasters-simulated) 0.1.5.**

Not affiliated with, sponsored by, or endorsed by SilverGold or the Sable team.

---

## The problem

Coasters Simulated has four hot paths that run inside Sable's pre-physics tick. Each one was written as a *same-tick* de-duplication guard:

```java
if (cached.gameTime == gameTime) return cached.value;
```

`gameTime` advances every tick, so that condition is only ever true inside a single tick. The guard never fires across ticks, and every one of these paths runs in full, every tick, forever.

The most expensive one is the cart bearing scan. It walks every chunk and section of a sublevel's plot and reads all 4096 block positions per section:

```
for each loaded chunk
  for each non-air section
    for x in 0..15, y in 0..15, z in 0..15
      getBlockState(x, y, z)
```

That scan runs for **every sublevel on the server**, including the ones that have no coaster cart in them at all — contraptions, machines, platforms, doors. In a base with a lot of assembled structures, that is a very large amount of block lookups per tick for a result that cannot change in a single tick anyway.

The other three paths are the same shape: per-sublevel rivet disassembly checks, a teardown-and-broadcast pass for sublevels that stop being carts, and a full train-link restore for every dimension.

## What this mod does

It replaces those same-tick guards with real throttles. Nothing about the coaster logic changes — the checks still run, just not 20 times a second for no reason.

- **Bearing plot scans** — cached for 40 ticks (2s) on sublevels that do contain a cart, and 100 ticks (5s) on sublevels that do not. The 100-tick case is where most of the win is.
- **Rivet disassembly checks** — the single-rivet enforcement runs at most once per 30 ticks, the self-hosted pop check once per 20 ticks.
- **Cart teardown broadcasts** — a sublevel that stops being a cart is cleaned up and broadcast once, on the transition, instead of on every tick thereafter.
- **Persisted train links** — restored once every 60 ticks (3s) per dimension instead of every tick.

All four intervals are configurable. Set any of them to `1` and that path behaves exactly like vanilla 0.1.5 — useful for A/B testing whether a problem you hit is caused by this mod or not.

### 1.1.0: the scan itself got cheaper, not just rarer

Throttling changes how *often* the scan runs; 1.1.0 changes how much a single run costs. The scan's only prune was `section.hasOnlyAir()`, which is `nonEmptyBlockCount == 0` — it skips sections that are **entirely** air. A section holding one piece of track, one stone, one block of wool still walked all 4096 positions. That guard now also asks the section's palette (`LevelChunkSection.maybeHas`) whether the section could contain the wanted block at all; if it cannot, the section is skipped outright.

The result is identical. A skipped section would have contributed zero matches, so the representative position, the match count and the cell list are unchanged. Sections with more than 256 distinct states answer `true` unconditionally and fall back to the original walk, so a "no" is a proof of absence, never a guess. This applies to the direct `scanBearingCells` calls in the rivet path and to the client-side fallback as well, not only to the throttled entry point.

## What it does not change

- **No gameplay changes.** No block, item, recipe, or physics constants are touched.
- **No client changes.** The network protocol is untouched, so clients can keep running the stock 0.1.5 jar. Install this on the server only.
- **No new controls or GUI.** It is invisible in game.

## Startup self-check

Mixin injections are only verified when the target class loads. If a class is never loaded, the log stays quiet — and "no errors" is easy to misread as "the patch is working". So this mod force-loads all five target classes once the server is up and checks that each of the eleven injected members actually landed:

```
[simcoastersfix] patch applied to CoasterCartBearingTickCache#simc$scans
[simcoastersfix] patch applied to CoasterCartPlotScan#simc$sectionsSkipped
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastRivetCheck
[simcoastersfix] patch applied to RivetDisassemblyEnforcer#simc$lastSelfHostCheck
[simcoastersfix] patch applied to CoasterCartTrackSnap#simc$teardownDone
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint#simc$lastRestore
[simcoastersfix] patch applied to BalloonTetherController#simc$balloonRestoreAt
[simcoastersfix] patch applied to BalloonTetherController#simc$balloonTickMemo
[simcoastersfix] patch applied to BalloonTetherController#simc$balloonPhysicsMemo
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint#simc$trainLinkTickMemo
[simcoastersfix] patch applied to CoasterPeerCurveHandleLengths#simc$handleLengths
[simcoastersfix] self-check OK: 11/11 mixin targets active
```

If you see `self-check FAILED`, the target mod version does not match and the injections did not find their hooks. The mixin config is `required: true` with `defaultRequire: 1`, so a mismatch normally fails the load outright rather than running half-patched — this check is a second line of defence.

## Configuration

`config/simcoastersfix-server.toml`, generated on first launch:

- `bearingScanTtlTicksWithCart` — default `40` — scan cache lifetime for sublevels that have a cart
- `bearingScanTtlTicksEmpty` — default `100` — scan cache lifetime for sublevels with no cart
- `rivetCheckIntervalTicks` — default `30` — minimum ticks between rivet checks per sublevel
- `rivetSelfHostCheckIntervalTicks` — default `20` — minimum ticks between self-hosted pop checks
- `trainLinkRestoreIntervalTicks` — default `60` — minimum ticks between train-link restores per dimension
- `balloonRestoreThrottle` / `balloonRestoreIntervalTicks` — default `true` / `40` — throttle the balloon restore scan (upstream runs it every physics tick over every sub-level)
- `balloonTickMemo` — default `true` — drop the duplicate balloon lookups inside one pre-physics tick
- `curveHandleLengthCache` — default `true` — cache bezier handle lengths per connection. Upstream re-resolves them with two synchronized WeakHashMap lookups plus a fresh string key on a path that runs per curve sample; measured at ~30% of tick time with carts running on a track, and reduced to zero once cached
- `balloonPhysicsMemo` — default `true` — drop the duplicate physics-pipeline velocity reads inside one balloon tick (7 call sites, identical results)
- `trainLinkTickMemo` — default `true` — drop the duplicate cart lookups inside one train-link tick

## Known limits

- **Cache lifetimes are visible latency.** A cart bearing cell that appears or disappears may take up to 40 ticks (with cart) or 100 ticks (no cart) to be noticed. If you place and remove carts constantly, lower `bearingScanTtlTicksEmpty`. 1.1.0 lowers the cost per scan, it does not shorten this delay — set the TTLs to `1` for per-tick accuracy.
- **Balloon restore is throttled.** Since 1.2.0 the restore scan runs every `balloonRestoreIntervalTicks` (40 ticks / 2 s) instead of every physics tick. Only a balloon that never went through the sub-level observer can be noticed that late; set the interval to `1` to restore the upstream cadence.
- **Section pruning has a floor.** Sections holding more than 256 distinct block states answer `true` to every palette query and keep the original 4096-position walk. Ordinary builds (track, air, a few decorations) stay far below that.
- **Still not the real algorithm fix.** Even with palette pruning the scan remains O(chunks × sections × palette size). The proper fix is an event-driven index (`plot → Set<BlockPos>` maintained on setBlock / destroyBlock) — that changes data structures and invariants, so it belongs upstream, not in a mixin.
- **Not a cure-all for TPS.** If your server is slow for other reasons, this will not fix those. Measure with `spark` before and after rather than assuming.

## Compatibility

- Minecraft **1.21.1**, NeoForge **21.1.x**
- Requires **Create 6.0.10+**, **Sable 2.0.0+**, **Create: Coasters Simulated 0.1.5+**
- **Server side only.** On a dedicated server the client does not need this jar: it registers no network payloads, and NeoForge 21.1 has no mod-list pairing handshake that would reject a client for missing it. In single-player the integrated server runs inside the client process, so the jar belongs in the client's `mods/` folder. Installing it on a pure client is harmless but does nothing.
- The mixins target 0.1.5 bytecode specifically. A future upstream release that renames or restructures these methods will fail the self-check rather than silently do nothing.

## Credits and license

- Original mod: **Create: Coasters Simulated** by SilverGold — [CurseForge](https://www.curseforge.com/minecraft/mc-mods/create-coasters-simulated) · [Modrinth](https://modrinth.com/mod/create-coasters-simulated) (MIT)
- Physics library: **Sable** by RyanHCode (PolyForm Shield 1.0.0) — linked at runtime, not redistributed
- This mod's icon is a modified version of the original mod's icon (the word "fix" added). Per the MIT license, the full attribution and license text ship inside the jar at `META-INF/NOTICE`.

This mod is **MIT** licensed. It contains no code from Create, Create: Coasters Simulated, or Sable.
