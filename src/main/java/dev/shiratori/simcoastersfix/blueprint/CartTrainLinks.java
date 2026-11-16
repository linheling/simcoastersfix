package dev.shiratori.simcoastersfix.blueprint;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Reader/writer for the per-sub-level cart train link list.
 *
 * <p>Coasters stores couplings in {@link ServerSubLevel#getUserDataTag()} (keys are duplicated from
 * {@code CoasterCartTrainLinkData}, which is package-private). Sable Photomancy's exporter never
 * copies a sub-level's user data, so a round trip silently drops every coupling.</p>
 */
final class CartTrainLinks {
    static final String LINKS = "simulatedcoasters:cart_train_links";
    static final String LEGACY_LINK = "simulatedcoasters:cart_train_link";
    static final String PARTNER = "partner";
    static final String DISTANCE = "distance";

    private CartTrainLinks() {
    }

    /** The raw entries (each is a {@code {partner, distance}} compound), or {@code null} when absent. */
    @Nullable
    static ListTag read(ServerSubLevel sub) {
        CompoundTag root = sub.getUserDataTag();
        if (root == null) {
            return null;
        }
        if (root.contains(LINKS, 9)) {
            return root.getList(LINKS, 10);
        }
        if (root.contains(LEGACY_LINK, 10)) {
            ListTag single = new ListTag();
            single.add(root.getCompound(LEGACY_LINK));
            return single;
        }
        return null;
    }

    /** Replaces the link list, leaving any unrelated user data alone. */
    static void write(ServerSubLevel sub, List<CompoundTag> links) {
        CompoundTag root = sub.getUserDataTag();
        root = root == null ? new CompoundTag() : root.copy();
        root.remove(LEGACY_LINK);
        if (links.isEmpty()) {
            root.remove(LINKS);
        } else {
            ListTag list = new ListTag();
            list.addAll(links);
            root.put(LINKS, list);
        }
        sub.setUserDataTag(root.isEmpty() ? null : root);
    }

    static CompoundTag entry(UUID partner, double distance) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(PARTNER, partner);
        tag.putDouble(DISTANCE, distance);
        return tag;
    }

    static UUID partnerOf(Tag tag) {
        return tag instanceof CompoundTag compound && compound.hasUUID(PARTNER) ? compound.getUUID(PARTNER) : null;
    }

    static double distanceOf(Tag tag) {
        return tag instanceof CompoundTag compound && compound.contains(DISTANCE, 6) ? compound.getDouble(DISTANCE) : 0.0;
    }
}
