package dev.shiratori.simcoastersfix.blueprint;

import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector3dc;

/**
 * Small NBT helpers for turning "an absolute position in this world" into "a position inside the
 * blueprint" and back again.
 *
 * <p>Only the pieces we need are implemented; Sable Photomancy ships an equivalent class in its own
 * {@code compat} package, but that is an internal package, so this mod keeps its own copy.</p>
 */
final class BlueprintRefs {
    static final String REF_SUB_LEVEL_ID = "sub_level_id";
    static final String REF_LOCAL_POS = "local_pos";

    private BlueprintRefs() {
    }

    /** Encodes a (blueprint sub-level id, local position) pair. */
    static CompoundTag writeRef(BlueprintBlockRef ref) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(REF_SUB_LEVEL_ID, ref.subLevelId());
        tag.put("local_pos", writePos(ref.localPos()));
        return tag;
    }

    /** Decodes what {@link #writeRef} wrote; empty if the payload is missing or malformed. */
    static Optional<BlueprintBlockRef> readRef(CompoundTag parent, String key) {
        if (!parent.contains(key, 10)) {
            return Optional.empty();
        }
        CompoundTag ref = parent.getCompound(key);
        if (!ref.contains(REF_SUB_LEVEL_ID, 3) || !ref.contains(REF_LOCAL_POS, 10)) {
            return Optional.empty();
        }
        return Optional.of(new BlueprintBlockRef(ref.getInt(REF_SUB_LEVEL_ID), readPos(ref.getCompound(REF_LOCAL_POS))));
    }

    static CompoundTag writePos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    static BlockPos readPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    /**
     * Floors a blueprint origin (which is a {@code Vec3}, not a block corner) to the block position
     * that offsets are measured from. Save and place both use this so the two agree.
     */
    static BlockPos originBlock(Vector3dc origin) {
        return new BlockPos(
                (int) Math.floor(origin.x()),
                (int) Math.floor(origin.y()),
                (int) Math.floor(origin.z()));
    }
}
