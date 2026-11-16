package dev.shiratori.simcoastersfix.blueprint;

import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSubLevelRef;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.silvergold.simulatedcoasters.balloon.BalloonBlockEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Re-points a balloon at the pasted copy of the sub-level it was tethered to.
 *
 * <p>A balloon keeps {@code HostSubLevel} — the raw uuid of the sub-level that owns the block it is
 * tied to. A blueprint paste always allocates fresh sub-level uuids, so after a paste the balloon no
 * longer resolves its host and never gets positioned. The balloon's own {@code HostPos} and
 * {@code HostLatticePos} are already plot-local, so only the uuid needs translating.</p>
 */
final class BalloonHostBlueprintMapper implements SableBlueprintBlockMapper {
    private static final String HOST_SUB_LEVEL = "HostSubLevel";
    private static final String HOST_SOURCE = "simcoastersfix:balloon_host_source";
    private static final String HOST_BLUEPRINT_ID = "simcoastersfix:balloon_host_blueprint";

    @Nullable
    @Override
    public CompoundTag save(BlueprintBlockSaveContext context, @Nullable CompoundTag defaultTag) {
        if (defaultTag == null || !(context.blockEntity() instanceof BalloonBlockEntity balloon)) {
            return defaultTag;
        }

        CompoundTag tag = defaultTag.copy();
        Optional<BlueprintSubLevelRef> ref = context.subLevelRef(balloon.hostSubLevelId());
        if (ref.isPresent()) {
            // Store the *source* sub-level identity; the placed uuid is only known after placement.
            tag.putUUID(HOST_SOURCE, ref.get().sourceUuid());
            tag.putInt(HOST_BLUEPRINT_ID, ref.get().subLevelId());
        }
        return tag;
    }

    @Override
    public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
        if (!tag.hasUUID(HOST_SOURCE)) {
            return;
        }
        UUID placed = context.session().mapSubLevel(tag.getUUID(HOST_SOURCE));
        if (placed != null) {
            tag.putUUID(HOST_SUB_LEVEL, placed);
        }
        tag.remove(HOST_SOURCE);
        tag.remove(HOST_BLUEPRINT_ID);
    }
}
