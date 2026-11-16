# Changelog

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
