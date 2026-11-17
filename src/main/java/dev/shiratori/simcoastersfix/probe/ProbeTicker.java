package dev.shiratori.simcoastersfix.probe;

import dev.shiratori.simcoastersfix.Simcoastersfix;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the {@link Probes} window clock.
 *
 * <p>Deliberately separate from {@code PatchSelfCheck}: that one runs once at server start, this one
 * runs every tick, and keeping them apart means a probe problem can never be confused with a
 * missing patch. Fires on the server thread, which is where every probe counter is written.
 */
@EventBusSubscriber(modid = Simcoastersfix.MODID)
public final class ProbeTicker {
    private ProbeTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Probes.tick();
    }
}
