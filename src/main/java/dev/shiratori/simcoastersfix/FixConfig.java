package dev.shiratori.simcoastersfix;

import dev.shiratori.simcoastersfix.probe.Probes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FixConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue BEARING_TTL_WITH_CART = BUILDER
            .comment("Ticks a bearing plot scan stays cached for a sublevel that HAS a cart bearing cell.",
                    "1 = same-tick only. simulatedcoasters 0.1.6 already resolves membership from an",
                    "event-driven index, so a longer TTL no longer saves a plot scan - it only widens the",
                    "window where a cart is 'not observable' and can get its couplings deleted.")
            .defineInRange("bearingScanTtlTicksWithCart", 1, 1, 72000);

    public static final ModConfigSpec.IntValue BEARING_TTL_EMPTY = BUILDER
            .comment("Same, for a sublevel with NO cart bearing cell.")
            .defineInRange("bearingScanTtlTicksEmpty", 1, 1, 72000);

    public static final ModConfigSpec.IntValue RIVET_INTERVAL = BUILDER
            .comment("Minimum ticks between rivet 'enforce single rivet' checks per sublevel.",
                    "1 = leave it to upstream: 0.1.6 runs the pass once per game time and only over",
                    "rivets plus dirty plots (RivetPlotMembership). Raising this only delays dirty",
                    "handling by that many ticks.")
            .defineInRange("rivetCheckIntervalTicks", 1, 1, 72000);

    public static final ModConfigSpec.IntValue SELF_HOST_INTERVAL = BUILDER
            .comment("Minimum ticks between rivet 'pop if self-hosted' checks per sublevel. 1 = upstream.",
                    "See rivetCheckIntervalTicks.")
            .defineInRange("rivetSelfHostCheckIntervalTicks", 1, 1, 72000);

    public static final ModConfigSpec.BooleanValue BALLOON_RESTORE_THROTTLE = BUILDER
            .comment("Throttle BalloonTetherController.maybeRestoreTethers.",
                    "Upstream still guards it with 'last != gameTime', which is true every tick, so it rescans",
                    "every sub-level on every physics tick to re-register balloons that are already registered.",
                    "Disable to restore the upstream per-tick behaviour.")
            .define("balloonRestoreThrottle", true);

    public static final ModConfigSpec.IntValue BALLOON_RESTORE_INTERVAL = BUILDER
            .comment("Minimum ticks between balloon restore scans when balloonRestoreThrottle is on (40 = 2s).",
                    "Worst case: a balloon that never went through the sub-level observer takes this long",
                    "to be tethered after it appears. Set to 1 for the upstream behaviour.")
            .defineInRange("balloonRestoreIntervalTicks", 40, 1, 72000);

    public static final ModConfigSpec.BooleanValue BALLOON_TICK_MEMO = BUILDER
            .comment("Memoise the balloon lookups inside one BalloonTetherController.onPrePhysicsTick.",
                    "The hook resolves each balloon's sub-level, block entity and host twice (once per pass);",
                    "this drops the second lookup of each. Behaviour is identical - nothing mutates inside one tick.")
            .define("balloonTickMemo", true);

    public static final ModConfigSpec.BooleanValue TRAIN_LINK_TICK_MEMO = BUILDER
            .comment("Memoise the cart lookups inside one CoasterCartTrainLinkConstraint.tick.",
                    "Each active link resolves cart A and cart B, and the same UUIDs are resolved again on the",
                    "next tick. Only the duplicate within a single tick is dropped; behaviour is identical.")
            .define("trainLinkTickMemo", true);

    public static final ModConfigSpec.BooleanValue BALLOON_PHYSICS_MEMO = BUILDER
            .comment("Memoise the physics-pipeline velocity reads inside one balloon pre-physics tick.",
                    "The balloon hook applies queued forces, so a body's velocity cannot change between two",
                    "reads in the same tick; the duplicate reads are dropped. Callers still receive a private",
                    "copy, so physics behaviour is unchanged.")
            .define("balloonPhysicsMemo", true);

    public static final ModConfigSpec.BooleanValue CURVE_HANDLE_LENGTH_CACHE = BUILDER
            .comment("Cache canonical bezier handle lengths per connection.",
                    "Upstream resolves them with two synchronized WeakHashMap lookups plus a freshly built",
                    "string key, on a path that runs per curve sample and re-enters itself through the",
                    "getHandleLength mixin. Measured as the hottest frame on the server tick thread.",
                    "Invalidated exactly by the upstream revision counter, which every writer bumps.")
            .define("curveHandleLengthCache", true);

    public static final ModConfigSpec.BooleanValue PROTECT_CART_LINKS = BUILDER
            .comment("Keep cart-train couplings when a cart is merely unobservable.",
                    "Off by default in 1.5.0: with the bearing scan TTL at 1 tick there is no stale negative",
                    "to guard against, and simulatedcoasters 0.1.6's membership index already refuses to",
                    "answer 'gone' for a plot that has not been populated yet.",
                    "Turn this on if you raise bearingScanTtlTicks* above ~2.")
            .define("protectCartLinks", false);

    public static final ModConfigSpec.BooleanValue HOTSPOT_PROBES = BUILDER
            .comment("Log one line per window with the cost of the hot spots simulatedcoasters 0.1.6 introduced:",
                    "the per-tick membership reconcile, the ready-chunk count, the membership lookup that",
                    "validates a block per cart, the rivet live-set pass, and the server thread's allocation",
                    "rate. Observation only - no behaviour change, two nanoTime calls per invocation.",
                    "The numbers are how the 'new hot spot' guesses in UPSTREAM-0.1.6.md get proven or dropped.")
            .define("hotspotProbes", true);

    public static final ModConfigSpec.IntValue HOTSPOT_PROBE_INTERVAL = BUILDER
            .comment("Ticks per probe log window (600 = 30 s at 20 TPS).")
            .defineInRange("hotspotProbeIntervalTicks", 600, 20, 72000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private FixConfig() {
    }
    static void apply() {
        FixLimits.bearingTtlWithCart = get(BEARING_TTL_WITH_CART, FixLimits.bearingTtlWithCart);
        FixLimits.bearingTtlEmpty = get(BEARING_TTL_EMPTY, FixLimits.bearingTtlEmpty);
        FixLimits.rivetIntervalTicks = get(RIVET_INTERVAL, FixLimits.rivetIntervalTicks);
        FixLimits.selfHostIntervalTicks = get(SELF_HOST_INTERVAL, FixLimits.selfHostIntervalTicks);
        FixLimits.balloonRestoreThrottle = get(BALLOON_RESTORE_THROTTLE, FixLimits.balloonRestoreThrottle);
        FixLimits.balloonRestoreIntervalTicks = get(BALLOON_RESTORE_INTERVAL, FixLimits.balloonRestoreIntervalTicks);
        FixLimits.balloonTickMemo = get(BALLOON_TICK_MEMO, FixLimits.balloonTickMemo);
        FixLimits.trainLinkTickMemo = get(TRAIN_LINK_TICK_MEMO, FixLimits.trainLinkTickMemo);
        FixLimits.balloonPhysicsMemo = get(BALLOON_PHYSICS_MEMO, FixLimits.balloonPhysicsMemo);
        FixLimits.curveHandleLengthCache = get(CURVE_HANDLE_LENGTH_CACHE, FixLimits.curveHandleLengthCache);
        FixLimits.protectCartLinks = get(PROTECT_CART_LINKS, FixLimits.protectCartLinks);
        Probes.enabled = get(HOTSPOT_PROBES, Probes.enabled);
        Probes.flushIntervalTicks = get(HOTSPOT_PROBE_INTERVAL, Probes.flushIntervalTicks);
        Simcoastersfix.LOGGER.info(
                "[{}] limits: bearingTtl(cart/empty)={}/{}, rivet={}, selfHost={},"
                        + " balloonRestore={}({}), balloonMemo={}, balloonPhysicsMemo={}, trainLinkMemo={},"
                        + " curveHandleLengthCache={}, protectCartLinks={}, probes={}({})",
                Simcoastersfix.MODID,
                FixLimits.bearingTtlWithCart, FixLimits.bearingTtlEmpty,
                FixLimits.rivetIntervalTicks, FixLimits.selfHostIntervalTicks,
                FixLimits.balloonRestoreThrottle, FixLimits.balloonRestoreIntervalTicks,
                FixLimits.balloonTickMemo, FixLimits.balloonPhysicsMemo, FixLimits.trainLinkTickMemo,
                FixLimits.curveHandleLengthCache, FixLimits.protectCartLinks,
                Probes.enabled, Probes.flushIntervalTicks);
    }
    private static int get(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
    private static boolean get(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
    @EventBusSubscriber(modid = Simcoastersfix.MODID)
    public static final class Reloader {
        private Reloader() {
        }
        @SubscribeEvent
        public static void onLoading(ModConfigEvent.Loading event) {
            apply();
        }
        @SubscribeEvent
        public static void onReloading(ModConfigEvent.Reloading event) {
            apply();
        }
    }
}
