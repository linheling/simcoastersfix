package dev.shiratori.simcoastersfix.probe;

import dev.shiratori.simcoastersfix.Simcoastersfix;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Verifies that the probe mixins actually landed.
 *
 * <p>Needed because the probe config runs with {@code required=false} and {@code defaultRequire=0}:
 * that is deliberate (a future upstream must lose probe data, not crash), but it also means a probe
 * that silently stops matching looks exactly like a probe that is measuring zero cost. Each probe
 * mixin therefore adds one {@code @Unique} constant, and this check looks for it &mdash; the same
 * trick {@code PatchSelfCheck} uses for the load-bearing patches.
 */
@EventBusSubscriber(modid = Simcoastersfix.MODID)
public final class ProbeSelfCheck {
    private static final String[][] TARGETS = {
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartServerMembership", "simc$cartMembershipProbe"},
            {"dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan", "simc$plotScanProbe"},
            {"dev.silvergold.simulatedcoasters.rivet.RivetPlotMembership", "simc$rivetMembershipProbe"},
    };

    private ProbeSelfCheck() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        int applied = 0;
        for (String[] target : TARGETS) {
            String className = target[0];
            String anchor = target[1];
            try {
                Class.forName(className).getDeclaredField(anchor);
                applied++;
            } catch (ClassNotFoundException e) {
                Simcoastersfix.LOGGER.warn("[{}] probe target class absent, skipped: {}",
                        Simcoastersfix.MODID, className);
            } catch (Throwable e) {
                Simcoastersfix.LOGGER.warn("[{}] probe NOT applied to {}#{} ({})",
                        Simcoastersfix.MODID, className, anchor, e.getClass().getSimpleName());
            }
        }
        if (applied == TARGETS.length) {
            Simcoastersfix.LOGGER.info("[{}] probe self-check OK: {}/{} probe mixins active",
                    Simcoastersfix.MODID, applied, TARGETS.length);
        } else {
            Simcoastersfix.LOGGER.warn("[{}] probe self-check: only {}/{} probe mixins active"
                            + " (the probe config is optional, so this is a data gap, not a failure)",
                    Simcoastersfix.MODID, applied, TARGETS.length);
        }
    }
}
