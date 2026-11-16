package dev.shiratori.simcoastersfix;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(Simcoastersfix.MODID)
public class Simcoastersfix {
    public static final String MODID = "simcoastersfix";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Sable Photomancy's mod id; the blueprint bridge is optional. */
    public static final String PHOTOMANCY_MODID = "sable_schematic_api";

    public Simcoastersfix(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, FixConfig.SPEC);
        modEventBus.addListener(this::commonSetup);
        LOGGER.info("[{}] server perf patch armed (4 mixin targets)", MODID);
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
