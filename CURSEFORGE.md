# Coaster Blueprint Compat (Unofficial)

**Unofficial Sable Photomancy blueprint bridge for [Create: Coasters Simulated](https://modrinth.com/mod/create-coasters-simulated) 0.1.6.**

Not affiliated with, sponsored by, or endorsed by SilverGold, the Sable team or the Photomancy author.

---

## The problem

Photomancy's blueprint format carries exactly two things: block states inside a sub-level, and block entity NBT. Every reference that points *somewhere else* is copied verbatim — coordinates are not translated and uuids are not remapped.

Create: Coasters Simulated stores three such references, so a save/paste round trip silently loses data:

1. **Rivets disappear.** The rivet block entity remembers its host position. After a paste that position still points at the old world, so the disassembly check tears the rivet down on the next tick — the rivet was saved and placed, then deleted by the mod itself.
2. **Coupled carts come apart.** Couplings live in `ServerSubLevel#getUserDataTag()`, which Photomancy copies, but the runtime index (`CoasterCartTrainLinkConstraint`) is not rebuilt for the pasted sub-level.
3. **Balloons no longer stick to their block**, drifting off or attaching at the wrong place.

There is **no error in the log** for any of these. They are silent data loss.

## What this mod does

It registers two blueprint mappers and one `SableBlueprintEvent` hook through Photomancy's public extension points:

- **On save** the host reference of a rivet, the host of a balloon and the cart-train couplings are written into the blueprint in a form that survives the copy.
- **On place** those references are remapped onto the new sub-level ids, and the coupling index is rebuilt for the pasted carts.

The mod never modifies the Coasters jar and never touches gameplay code. See `BLUEPRINT_COMPAT.md` in the repository for the field-by-field design and the acceptance log.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.x
- Create 6.0.10+
- Sable 2.0.0+
- Create: Coasters Simulated **0.1.6** (0.1.5 is not supported by 2.0.0)
- Sable Photomancy (`sable_schematic_api`) 1.0.0+ — **optional, but without it this mod does nothing**

## 2.0.0 is a scope cut

Earlier releases (1.0.0 – 1.5.2) were a performance patch with this blueprint layer attached. Coasters Simulated 0.1.6 fixed the per-tick rescans those patches existed for and moved several of their injection points, so 2.0.0 drops the performance side entirely and keeps only the bridge. The last release that still contains the optimisations is 1.5.2.

**Upgrade warning:** 2.0.0 does not include the "re-verify before unlinking" guard that 1.4.1 added. Coasters Simulated 0.1.6 still has a regression where a sub-level classified before its plot finished populating is cached as "not a cart" forever, and the coupling of that cart is then deleted from the cart's persisted NBT — irreversibly. If your save contains coupled coaster trains, back it up before installing 2.0.0, or stay on 1.5.2.

## Install

Put the jar in `mods/` on the client and the server (single-player runs both in one process). There are no commands, no GUI and no config file. Nothing changes visually in game.

## License

MIT. The icon is a modified derivative of the original mod's artwork; the attribution and the full MIT text are in `META-INF/NOTICE` inside the jar.
