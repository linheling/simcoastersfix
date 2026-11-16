package dev.shiratori.simcoastersfix;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid = Simcoastersfix.MODID)
public final class PatchSelfCheck {
    private static final String[][] TARGETS = {
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartBearingTickCache", "simc$scans"},
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan", "simc$sectionsSkipped"},
            {"dev.silvergold.simulatedcoasters.rivet.RivetDisassemblyEnforcer", "simc$lastRivetCheck"},
            {"dev.silvergold.simulatedcoasters.rivet.RivetDisassemblyEnforcer", "simc$lastSelfHostCheck"},
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrackSnap", "simc$teardownDone"},
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint", "simc$lastRestore"},
            {"dev.silvergold.simulatedcoasters.balloon.BalloonTetherController", "simc$balloonRestoreAt"},
            {"dev.silvergold.simulatedcoasters.balloon.BalloonTetherController", "simc$balloonTickMemo"},
            {"dev.silvergold.simulatedcoasters.balloon.BalloonTetherController", "simc$balloonPhysicsMemo"},
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint", "simc$trainLinkTickMemo"},
            {"dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths", "simc$handleLengths"},
    };

    private PatchSelfCheck() {
    }

    /** Number of injected members the self-check expects; kept in one place so log lines cannot drift. */
    static int targetCount() {
        return TARGETS.length;
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
                Simcoastersfix.LOGGER.info("[{}] patch applied to {}#{}",
                        Simcoastersfix.MODID, clazz.getSimpleName(), injectedField);
            } catch (ClassNotFoundException e) {
                Simcoastersfix.LOGGER.warn("[{}] target class absent, skipped: {}", Simcoastersfix.MODID, className);
            } catch (Throwable e) {
                Simcoastersfix.LOGGER.error("[{}] patch NOT applied to {}#{} ({})",
                        Simcoastersfix.MODID, className, injectedField, e.getClass().getSimpleName());
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
