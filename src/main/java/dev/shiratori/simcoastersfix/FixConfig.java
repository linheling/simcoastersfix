package dev.shiratori.simcoastersfix;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FixConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue BEARING_TTL_WITH_CART = BUILDER
            .comment("A sublevel that HAS a coaster cart bearing cell: how many game ticks a bearing plot scan stays cached (40 = 2s).")
            .defineInRange("bearingScanTtlTicksWithCart", 40, 1, 72000);

    public static final ModConfigSpec.IntValue BEARING_TTL_EMPTY = BUILDER
            .comment("A sublevel with NO cart bearing cell: how many game ticks a bearing plot scan stays cached (100 = 5s).",
                    "This is the big win: most sublevels in a world carry no cart and were fully plot-scanned every tick.")
            .defineInRange("bearingScanTtlTicksEmpty", 100, 1, 72000);

    public static final ModConfigSpec.IntValue RIVET_INTERVAL = BUILDER
            .comment("Minimum ticks between rivet 'enforce single rivet' checks per sublevel (30).")
            .defineInRange("rivetCheckIntervalTicks", 30, 1, 72000);

    public static final ModConfigSpec.IntValue SELF_HOST_INTERVAL = BUILDER
            .comment("Minimum ticks between rivet 'pop if self-hosted' checks per sublevel (20).")
            .defineInRange("rivetSelfHostCheckIntervalTicks", 20, 1, 72000);

    public static final ModConfigSpec.IntValue LINK_RESTORE_INTERVAL = BUILDER
            .comment("Minimum ticks between persisted cart-train link restores per dimension (60 = 3s).")
            .defineInRange("trainLinkRestoreIntervalTicks", 60, 1, 72000);

    public static final ModConfigSpec.BooleanValue BALLOON_RESTORE_THROTTLE = BUILDER
            .comment("Throttle BalloonTetherController.maybeRestoreTethers.",
                    "Upstream guards it with 'last != gameTime', which is true every tick, so it rescans every",
                    "sub-level on every physics tick to re-register balloons that are already registered.",
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
                    "reads in the same tick; the duplicate reads and duplicate wakeUp calls are dropped.",
                    "Callers still receive a private copy, so physics behaviour is unchanged.")
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
                    "Upstream deletes a coupling (including the copy in the cart's persisted NBT) whenever the",
                    "bearing-cell scan comes back empty for the partner. Right after a world load that scan can",
                    "legitimately come back empty — the sub-level is in the container before its plot is",
                    "populated — and every coupling in the world is then erased. With this on, such a teardown",
                    "re-checks membership with a fresh scan and refuses when the answer is 'not observable'.",
                    "Disable to restore the upstream behaviour.")
            .define("protectCartLinks", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private FixConfig() {
    }
    static void apply() {
        FixLimits.bearingTtlWithCart = get(BEARING_TTL_WITH_CART, FixLimits.bearingTtlWithCart);
        FixLimits.bearingTtlEmpty = get(BEARING_TTL_EMPTY, FixLimits.bearingTtlEmpty);
        FixLimits.rivetIntervalTicks = get(RIVET_INTERVAL, FixLimits.rivetIntervalTicks);
        FixLimits.selfHostIntervalTicks = get(SELF_HOST_INTERVAL, FixLimits.selfHostIntervalTicks);
        FixLimits.linkRestoreIntervalTicks = get(LINK_RESTORE_INTERVAL, FixLimits.linkRestoreIntervalTicks);
        FixLimits.balloonRestoreThrottle = get(BALLOON_RESTORE_THROTTLE, FixLimits.balloonRestoreThrottle);
        FixLimits.balloonRestoreIntervalTicks = get(BALLOON_RESTORE_INTERVAL, FixLimits.balloonRestoreIntervalTicks);
        FixLimits.balloonTickMemo = get(BALLOON_TICK_MEMO, FixLimits.balloonTickMemo);
        FixLimits.trainLinkTickMemo = get(TRAIN_LINK_TICK_MEMO, FixLimits.trainLinkTickMemo);
        FixLimits.balloonPhysicsMemo = get(BALLOON_PHYSICS_MEMO, FixLimits.balloonPhysicsMemo);
        FixLimits.curveHandleLengthCache = get(CURVE_HANDLE_LENGTH_CACHE, FixLimits.curveHandleLengthCache);
        FixLimits.protectCartLinks = get(PROTECT_CART_LINKS, FixLimits.protectCartLinks);
        Simcoastersfix.LOGGER.info(
                "[{}] limits: bearingTtl(cart/empty)={}/{}, rivet={}, selfHost={}, linkRestore={},"
                        + " balloonRestore={}({}), balloonMemo={}, balloonPhysicsMemo={}, trainLinkMemo={},"
                        + " curveHandleLengthCache={}, protectCartLinks={}",
                Simcoastersfix.MODID,
                FixLimits.bearingTtlWithCart, FixLimits.bearingTtlEmpty,
                FixLimits.rivetIntervalTicks, FixLimits.selfHostIntervalTicks,
                FixLimits.linkRestoreIntervalTicks,
                FixLimits.balloonRestoreThrottle, FixLimits.balloonRestoreIntervalTicks,
                FixLimits.balloonTickMemo, FixLimits.balloonPhysicsMemo, FixLimits.trainLinkTickMemo,
                FixLimits.curveHandleLengthCache, FixLimits.protectCartLinks);
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
