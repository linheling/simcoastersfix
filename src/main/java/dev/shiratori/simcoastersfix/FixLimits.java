package dev.shiratori.simcoastersfix;

public final class FixLimits {
    public static int bearingTtlWithCart = 40;

    public static int bearingTtlEmpty = 100;

    public static int rivetIntervalTicks = 30;

    public static int selfHostIntervalTicks = 20;

    public static int linkRestoreIntervalTicks = 60;

    /** Throttle {@code BalloonTetherController.maybeRestoreTethers}, which upstream runs every tick. */
    public static boolean balloonRestoreThrottle = true;

    public static int balloonRestoreIntervalTicks = 40;

    /** Memoise the repeated balloon/sub-level/block-entity lookups inside one pre-physics tick. */
    public static boolean balloonTickMemo = true;

    /** Memoise the per-link cart lookups inside one {@code CoasterCartTrainLinkConstraint.tick}. */
    public static boolean trainLinkTickMemo = true;

    /** Memoise the repeated physics-pipeline velocity reads / duplicate wakes inside one balloon hook. */
    public static boolean balloonPhysicsMemo = true;

    /** Cache canonical bezier handle lengths per connection, invalidated by the upstream revision. */
    public static boolean curveHandleLengthCache = true;

    private FixLimits() {
    }
}
