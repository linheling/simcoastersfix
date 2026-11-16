package dev.shiratori.simcoastersfix.util;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Per-invocation memo for repeated lookups inside one tick hook.
 *
 * <p>{@code BalloonTetherController.onPrePhysicsTick} and
 * {@code CoasterCartTrainLinkConstraint.tick} resolve the same sub-level/block-entity/cart several
 * times per invocation, and the balloon path additionally reads the same physics-pipeline
 * velocities from several helpers. Two facts make memoising them exact rather than approximate:
 *
 * <ul>
 *   <li>Nothing inside those invocations mutates the sub-level container — a ref may leave the
 *       container, but the same lookups inside one invocation cannot disagree.
 *   <li>Impulses and forces are <em>queued</em> by the physics pipeline and only applied at the
 *       step, so a body's linear/angular velocity is constant for the whole pre-physics hook.
 *       Waking an already-awake body is idempotent.
 * </ul>
 *
 * <p>Scope, not timing: {@link #begin()} is called at the start of the hook and {@link #end()} at
 * its return, so a value can never outlive the invocation that produced it — no `gameTime`-based
 * staleness. {@link #begin()} clears unconditionally rather than counting depth, so an exception
 * thrown out of a hook (which skips {@link #end()}) cannot leave stale entries for the next
 * invocation. The hooks involved never nest; even if one ever did, an inner {@code end()} would
 * only drop the outer memo's hits, never serve a stale value.
 *
 * <p>{@link #MISS} vs {@link #NULL_VALUE}: a single pass cannot memoise "the answer was null"
 * otherwise, and the balloon/cart lookups legitimately return null.
 */
public final class TickEvalMemo {
    /** Returned by {@link #get(Object)} / {@link #getSlot(int, Object)} when nothing is memoised. */
    public static final Object MISS = new Object();

    /** Stored instead of a null answer; callers unwrap it back to null. */
    public static final Object NULL_VALUE = new Object();

    /** Default identity slot used by {@link #get(Object)} / {@link #put(Object, Object)}. */
    private static final int SLOT_DEFAULT = 0;
    /** One namespace per quantity that can be memoised for the same body. */
    private static final int SLOT_COUNT = 4;

    public static final int SLOT_LINEAR_VELOCITY = 1;
    public static final int SLOT_ANGULAR_VELOCITY = 2;

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    private TickEvalMemo() {
    }

    public static void begin() {
        Memo memo = MEMO.get();
        memo.clear();
        memo.active = true;
    }

    public static void end() {
        Memo memo = MEMO.get();
        memo.active = false;
        memo.clear();
    }

    public static boolean active() {
        return MEMO.get().active;
    }

    /** @return {@link #MISS}, {@link #NULL_VALUE}, or the memoised value */
    public static Object get(Object key) {
        Memo memo = MEMO.get();
        if (!memo.active) {
            return MISS;
        }
        if (key instanceof UUID id) {
            Object value = memo.byId.get(id);
            return value == null ? MISS : value;
        }
        return getSlot(SLOT_DEFAULT, key);
    }

    public static void put(Object key, @Nullable Object value) {
        Memo memo = MEMO.get();
        if (!memo.active) {
            return;
        }
        if (key instanceof UUID id) {
            memo.byId.put(id, value == null ? NULL_VALUE : value);
            return;
        }
        putSlot(SLOT_DEFAULT, key, value);
    }

    /**
     * Identity-keyed lookup in one namespace. Use distinct {@code slot} constants when the same
     * body is memoised for different quantities (velocity vs. wake state).
     */
    public static Object getSlot(int slot, Object key) {
        Memo memo = MEMO.get();
        if (!memo.active) {
            return MISS;
        }
        Object value = memo.byIdentity[slot].get(key);
        return value == null ? MISS : value;
    }

    public static void putSlot(int slot, Object key, @Nullable Object value) {
        Memo memo = MEMO.get();
        if (!memo.active) {
            return;
        }
        memo.byIdentity[slot].put(key, value == null ? NULL_VALUE : value);
    }

    /** Unwraps {@link #NULL_VALUE} back to {@code null}; any other value is returned as-is. */
    @Nullable
    public static Object unwrap(Object value) {
        return value == NULL_VALUE ? null : value;
    }

    private static final class Memo {
        private boolean active;
        private final Map<UUID, Object> byId = new HashMap<>();
        @SuppressWarnings("unchecked")
        private final IdentityHashMap<Object, Object>[] byIdentity = new IdentityHashMap[SLOT_COUNT];

        private Memo() {
            for (int i = 0; i < byIdentity.length; i++) {
                byIdentity[i] = new IdentityHashMap<>();
            }
        }

        private void clear() {
            byId.clear();
            for (IdentityHashMap<Object, Object> map : byIdentity) {
                map.clear();
            }
        }
    }
}
