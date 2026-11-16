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

    public static final ModConfigSpec SPEC = BUILDER.build();

    private FixConfig() {
    }
    static void apply() {
        FixLimits.bearingTtlWithCart = get(BEARING_TTL_WITH_CART, FixLimits.bearingTtlWithCart);
        FixLimits.bearingTtlEmpty = get(BEARING_TTL_EMPTY, FixLimits.bearingTtlEmpty);
        FixLimits.rivetIntervalTicks = get(RIVET_INTERVAL, FixLimits.rivetIntervalTicks);
        FixLimits.selfHostIntervalTicks = get(SELF_HOST_INTERVAL, FixLimits.selfHostIntervalTicks);
        FixLimits.linkRestoreIntervalTicks = get(LINK_RESTORE_INTERVAL, FixLimits.linkRestoreIntervalTicks);
        Simcoastersfix.LOGGER.info(
                "[{}] limits: bearingTtl(cart/empty)={}/{}, rivet={}, selfHost={}, linkRestore={}",
                Simcoastersfix.MODID,
                FixLimits.bearingTtlWithCart, FixLimits.bearingTtlEmpty,
                FixLimits.rivetIntervalTicks, FixLimits.selfHostIntervalTicks,
                FixLimits.linkRestoreIntervalTicks);
    }
    private static int get(ModConfigSpec.IntValue value, int fallback) {
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
