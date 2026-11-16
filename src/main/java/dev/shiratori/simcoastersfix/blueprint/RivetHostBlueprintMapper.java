package dev.shiratori.simcoastersfix.blueprint;

import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.silvergold.simulatedcoasters.rivet.RivetBlockEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Makes a rivet's host attachment survive a blueprint round trip.
 *
 * <p>A rivet is its own single-block sub-level. Its block entity remembers <em>where</em> it is
 * riveted as {@code HostPos} — an absolute, world-space block position — plus a raw {@code HostSubLevel}
 * uuid. Sable Photomancy copies that tag verbatim, so a pasted rivet still points at the coordinates
 * of the original build. Coasters then decides the host is gone and quietly tears the rivet down.</p>
 *
 * <p>The fix is to swap the absolute position for blueprint-relative data on save, and to resolve it
 * back to a concrete position <em>before</em> the block entity loads (so the "is my host still there"
 * check never sees a stale value).</p>
 */
final class RivetHostBlueprintMapper implements SableBlueprintBlockMapper {
    private static final String HOST_POS = "HostPos";
    private static final String HOST_SUB_LEVEL = "HostSubLevel";
    private static final String HOST_REF = "simcoastersfix:host_ref";
    private static final String HOST_OFFSET = "simcoastersfix:host_offset";

    @Nullable
    @Override
    public CompoundTag save(BlueprintBlockSaveContext context, @Nullable CompoundTag defaultTag) {
        if (defaultTag == null || !(context.blockEntity() instanceof RivetBlockEntity rivet)) {
            return defaultTag;
        }

        CompoundTag tag = defaultTag.copy();
        BlockPos host = rivet.hostPos();
        tag.remove(HOST_POS);
        tag.remove(HOST_SUB_LEVEL);

        // Always keep a relative fallback: it is what we use when the host block is not part of any
        // saved sub-level (riveted straight onto the world) or when a reference cannot be resolved.
        BlockPos origin = BlueprintRefs.originBlock(context.session().rootOrigin());
        tag.putLong(HOST_OFFSET, host.subtract(origin).asLong());

        Optional<BlueprintBlockRef> ref = context.blockRef(host);
        if (ref.isPresent()) {
            tag.put(HOST_REF, BlueprintRefs.writeRef(ref.get()));
        }
        return tag;
    }

    @Override
    public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
        if (!tag.contains(HOST_REF, 10) && !tag.contains(HOST_OFFSET, 4)) {
            return;
        }

        BlockPos resolved = null;
        if (tag.contains(HOST_REF, 10)) {
            Optional<BlueprintBlockRef> ref = BlueprintRefs.readRef(tag, HOST_REF);
            if (ref.isPresent()) {
                resolved = context.mapBlock(ref.get());
            }
        }
        if (resolved == null && tag.contains(HOST_OFFSET, 4)) {
            resolved = BlueprintRefs.originBlock(context.session().origin()).offset(BlockPos.of(tag.getLong(HOST_OFFSET)));
        }

        if (resolved != null) {
            tag.putLong(HOST_POS, resolved.asLong());
        }
        tag.remove(HOST_REF);
        tag.remove(HOST_OFFSET);
    }
}
