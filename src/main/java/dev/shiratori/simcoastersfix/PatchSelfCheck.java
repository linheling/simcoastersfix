package dev.shiratori.simcoastersfix;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid = Simcoastersfix.MODID)
public final class PatchSelfCheck {
    private static final String[][] TARGETS = {
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartBearingTickCache", "simc$scans"},
            {"dev.silvergold.simulatedcoasters.rivet.RivetDisassemblyEnforcer", "simc$lastRivetCheck"},
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrackSnap", "simc$teardownDone"},
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint", "simc$lastRestore"},
    };

    private PatchSelfCheck() {
    }
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        int applied = 0;
        for (String[] target : TARGETS) {
            String className = target[0];
            String injectedField = target[1];
            try {
                Class<?> clazz = Class.forName(className);
                clazz.getDeclaredField(injectedField);
                applied++;
                Simcoastersfix.LOGGER.info("[{}] patch applied to {}", Simcoastersfix.MODID, clazz.getSimpleName());
            } catch (ClassNotFoundException e) {
                Simcoastersfix.LOGGER.warn("[{}] target class absent, skipped: {}", Simcoastersfix.MODID, className);
            } catch (Throwable e) {
                Simcoastersfix.LOGGER.error("[{}] patch NOT applied to {} ({})",
                        Simcoastersfix.MODID, className, e.getClass().getSimpleName());
            }
        }
        if (applied == TARGETS.length) {
            Simcoastersfix.LOGGER.info("[{}] self-check OK: {}/{} mixin targets active",
                    Simcoastersfix.MODID, applied, TARGETS.length);
        } else {
            Simcoastersfix.LOGGER.error("[{}] self-check FAILED: only {}/{} mixin targets active",
                    Simcoastersfix.MODID, applied, TARGETS.length);
        }
    }
}
