package dev.shiratori.simcoastersfix;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * Unofficial Sable Photomancy blueprint bridge for Create: Coasters Simulated.
 *
 * <p>The mod does exactly one thing: when {@code sable_schematic_api} (Sable Photomancy) is installed,
 * register blueprint mappers so rivet hosts, balloon hosts and cart train couplings survive a
 * save/paste round trip. Without Photomancy it loads, logs that and does nothing.
 *
 * <p>2.0.0 dropped the performance mixins: simulatedcoasters 0.1.6 replaced the per-tick rescans they
 * throttled with event-driven member indexes, so those patches had lost their target. The last build
 * that carried them is tagged {@code archive-1.5.2-perf}.
 */
@Mod(Simcoastersfix.MODID)
public class Simcoastersfix {
    public static final String MODID = "simcoastersfix";

    public static final Logger LOGGER = LogUtils.getLogger();

    /** Photomancy's mod id; the whole blueprint layer is optional and only loaded when it is present. */
    public static final String PHOTOMANCY_MODID = "sable_schematic_api";

    public Simcoastersfix(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        LOGGER.info("[{}] blueprint compat armed", MODID);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded(PHOTOMANCY_MODID)) {
                dev.shiratori.simcoastersfix.blueprint.BlueprintCompat.register();
            } else {
                LOGGER.info("[{}] {} not installed, blueprint compatibility disabled", MODID, PHOTOMANCY_MODID);
            }
        });
    }
}
