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

## What it does not change

- **No gameplay changes.** No block, item, recipe, or physics constants are touched.
- **No client changes.** The network protocol is untouched, so clients can keep running the stock 0.1.5 jar. Install this on the server only.
- **No new controls or GUI.** It is invisible in game.

## Startup self-check

Mixin injections are only verified when the target class loads. If a class is never loaded, the log stays quiet — and "no errors" is easy to misread as "the patch is working". So this mod force-loads all four target classes once the server is up and checks that each injection actually landed:

```
[simcoastersfix] patch applied to CoasterCartBearingTickCache
[simcoastersfix] patch applied to RivetDisassemblyEnforcer
[simcoastersfix] patch applied to CoasterCartTrackSnap
[simcoastersfix] patch applied to CoasterCartTrainLinkConstraint
[simcoastersfix] self-check OK: 4/4 mixin targets active
```

If you see `self-check FAILED`, the target mod version does not match and the injections did not find their hooks. The mixin config is `required: true` with `defaultRequire: 1`, so a mismatch normally fails the load outright rather than running half-patched — this check is a second line of defence.

## Configuration

`config/simcoastersfix-server.toml`, generated on first launch:

- `bearingScanTtlTicksWithCart` — default `40` — scan cache lifetime for sublevels that have a cart
- `bearingScanTtlTicksEmpty` — default `100` — scan cache lifetime for sublevels with no cart
- `rivetCheckIntervalTicks` — default `30` — minimum ticks between rivet checks per sublevel
- `rivetSelfHostCheckIntervalTicks` — default `20` — minimum ticks between self-hosted pop checks
- `trainLinkRestoreIntervalTicks` — default `60` — minimum ticks between train-link restores per dimension

## Known limits

- **Cache lifetimes are visible latency.** A cart bearing cell that appears or disappears may take up to 40 ticks (with cart) or 100 ticks (no cart) to be noticed. If you place and remove carts constantly, lower `bearingScanTtlTicksEmpty`.
- **This treats the symptom's cause, not the algorithm.** The scan itself is still O(chunks × sections × 4096). Throttling cuts how often it runs, not how expensive a single run is. The proper fix is a per-section non-air block index, and that belongs upstream.
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
