package dev.shiratori.simcoastersfix;

public final class FixLimits {
    /**
     * Same-tick only. simulatedcoasters 0.1.6 resolves cart membership from an event-driven index
     * ({@code CoasterCartServerMembership}), so a cross-tick TTL no longer saves a plot scan &mdash;
     * it only re-introduces the "cannot see it, so it must be gone" window that 1.4.1 had to guard
     * against. 1 = deduplicate within a tick, nothing more.
     */
    public static int bearingTtlWithCart = 1;

    /** See {@link #bearingTtlWithCart}. */
    public static int bearingTtlEmpty = 1;

    /**
     * 1 = leave the per-tick rivet pass to upstream's {@code RivetPlotMembership} guard (0.1.6 runs
     * it once per game time and only over rivets + dirty plots). Raise this only to A/B the old
     * every-N-ticks throttle.
     */
    public static int rivetIntervalTicks = 1;

    /** See {@link #rivetIntervalTicks}. */
    public static int selfHostIntervalTicks = 1;

    /**
     * Still worth it: {@code BalloonTetherController.maybeRestoreTethers} is unchanged in 0.1.6 and
     * still guards with {@code last != gameTime}, i.e. it walks every sub-level every physics tick.
     */
    public static boolean balloonRestoreThrottle = true;

    public static int balloonRestoreIntervalTicks = 40;

    /** Memoise the repeated balloon/sub-level/block-entity lookups inside one pre-physics tick. */
    public static boolean balloonTickMemo = true;

    /** Memoise the per-link cart lookups inside one {@code CoasterCartTrainLinkConstraint.tick}. */
    public static boolean trainLinkTickMemo = true;

    /** Memoise the repeated physics-pipeline velocity reads inside one balloon hook. */
    public static boolean balloonPhysicsMemo = true;

    /** Cache canonical bezier handle lengths per connection, invalidated by the upstream revision. */
    public static boolean curveHandleLengthCache = true;

    /**
     * On by default, and 1.5.1 had to put it back there.
     *
     * <p>1.5.0 assumed 0.1.6's membership index made this redundant. A live session proved the
     * opposite: 10,240 refusals across 78 carts, zero upstream unlinks, and the index never
     * self-corrected (see {@code OPTIMIZATION.md} &sect;15.4). A sub-level classified before its
     * plot was populated is cached as "not a cart" forever, because the only re-check condition is
     * {@code readyChunkCount}, which does not change when a chunk object already existed.
     *
     * <p>So the guard stays on: it re-scans before anything destructive, and only ever runs on the
     * teardown path.
     */
    public static boolean protectCartLinks = true;

    private FixLimits() {
    }
}
