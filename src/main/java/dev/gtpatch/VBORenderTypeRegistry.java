package dev.gtpatch;

import codechicken.lib.render.buffer.VBORenderType;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reactive registry for live {@link VBORenderType} instances.
 *
 * <h3>Problem</h3>
 * GTCEu's multiblock preview renderer creates a fresh set of VBORenderType
 * instances every time a preview is opened, rebuilt, or the multiblock
 * structure changes. Each VBORenderType allocates one VBO + one VAO in its
 * constructor. When GTCEu rebuilds, new VBORenderType instances are created
 * for the same render-type slots; the old ones are overwritten in whatever
 * collection GTCEu uses and simply abandoned — close() is never called.
 *
 * <h3>Key identity fact (bytecode-confirmed)</h3>
 * {@code DelegateRenderType.<init>(RenderType, VertexFormat)} at bytecode
 * offset 2 reads {@code parent.f_110133_} (the name String) and passes it
 * as the first argument to {@code RenderType.<init>(String, ...)}. Therefore:
 * <ul>
 *   <li>Two VBORenderType instances constructed with the same parent
 *       RenderType will have the <em>same name String</em>.</li>
 *   <li>{@code instance.toString()} is a stable, per-slot key that uniquely
 *       identifies the render-type "slot" a VBORenderType occupies.</li>
 *   <li>When a second instance appears for the same slot, the first instance
 *       has been abandoned and its VBO + VAO will never be freed unless we
 *       close it proactively.</li>
 * </ul>
 *
 * <h3>Fix — two-tier reactive + safety-net</h3>
 * <ol>
 *   <li><b>Reactive tier</b>: {@code BY_NAME} is a
 *       {@link ConcurrentHashMap} keyed by {@code instance.toString()}.
 *       When {@link #register} is called for a slot that already has an
 *       entry, the old instance is closed <em>immediately</em> — before the
 *       caller's constructor even returns to GTCEu. This is the primary fix:
 *       it limits live VBOs to exactly one per unique render-type name at any
 *       given time.</li>
 *   <li><b>Safety-net tier</b>: {@link #closeAll} drains any remaining live
 *       instances when {@code cleanPreview()} is called. This catches
 *       instances that were never replaced (e.g., the last set created before
 *       the preview is closed).</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * VBORenderType instances may be constructed on the background compile thread
 * ({@code lambda$renderCacheBuffer$5} in WorldSceneRenderer launches a
 * new Thread). {@link ConcurrentHashMap} provides the necessary atomicity:
 * {@link ConcurrentHashMap#put} and {@link ConcurrentHashMap#remove(Object,Object)}
 * are individually atomic. The conditional remove {@code remove(key, expected)}
 * used in {@link #unregister} ensures that a racing {@link #register} for the
 * same slot cannot accidentally wipe out the newer instance.
 */
public final class VBORenderTypeRegistry {

    /**
     * Maps render-type name → current live VBORenderType for that slot.
     *
     * <p>Invariant: at most one VBORenderType per name is tracked. When a new
     * instance is registered for an existing name, the old one is closed and
     * replaced atomically (from the perspective of subsequent {@code get}
     * calls).
     */
    private static final ConcurrentHashMap<String, VBORenderType> BY_NAME =
            new ConcurrentHashMap<>();

    private VBORenderTypeRegistry() {}

    /**
     * Called from {@code VBORenderTypeLeakMixin.gtpatch_registerVBO} at the
     * RETURN of {@code VBORenderType.<init>}.
     *
     * <p><b>Reactive close</b>: if {@code BY_NAME} already contains a
     * <em>different</em> instance for the same render-type slot, that old
     * instance is closed immediately. This is the primary leak prevention
     * mechanism — it fires every time GTCEu replaces one VBORenderType with
     * another for the same render-type slot during a scene rebuild.
     *
     * <p>The old instance's {@code close()} will trigger
     * {@link #unregister(VBORenderType)}, which calls
     * {@code BY_NAME.remove(key, old)}. Because we already put {@code instance}
     * into the map before calling {@code old.close()}, the conditional remove
     * sees that the current value is {@code instance}, not {@code old}, and is
     * a no-op — the new instance stays in the map.
     */
    public static void register(VBORenderType instance) {
        String key = instance.toString();
        VBORenderType old = BY_NAME.put(key, instance);
        if (old != null && old != instance) {
            // Reactive close: old instance is being replaced — free its GL resources now.
            try {
                old.close();   // triggers unregister(old) → BY_NAME.remove(key, old) → no-op
            } catch (Exception ignored) {}
        }
    }

    /**
     * Called from {@code VBORenderTypeLeakMixin.gtpatch_unregisterVBO} at the
     * HEAD of {@code VBORenderType.close()}.
     *
     * <p>Uses {@link ConcurrentHashMap#remove(Object, Object)} (conditional
     * remove) so that this call never accidentally removes a <em>newer</em>
     * instance that was placed into the map for the same slot by a racing
     * {@link #register} call.
     */
    public static void unregister(VBORenderType instance) {
        // Conditional remove: only removes if the current value IS this instance.
        // If register() already replaced it with a newer instance, this is a no-op.
        BY_NAME.remove(instance.toString(), instance);
    }

    /**
     * Close all currently-tracked VBORenderType instances (safety-net tier).
     *
     * <p>Called from
     * {@code MultiblockInWorldPreviewRendererMixin.gtpatch_teardownBeforeClean}
     * at the HEAD of {@code cleanPreview()}. At this point the reactive tier
     * will have already closed all replaced instances; {@code closeAll} only
     * needs to close the <em>last</em> set of instances — those that were
     * never replaced because the preview was closed before a subsequent
     * rebuild occurred.
     *
     * <p>Drains the map by iterating a snapshot of values so that each
     * {@code close()} → {@code unregister()} call's conditional-remove
     * operates on a stable map state.
     */
    public static void closeAll(Logger log) {
        if (BY_NAME.isEmpty()) return;

        List<VBORenderType> snapshot = new ArrayList<>(BY_NAME.values());
        int closed = 0;
        for (VBORenderType vbo : snapshot) {
            try {
                vbo.close();   // triggers unregister() → conditional-remove from BY_NAME
                closed++;
            } catch (Exception e) {
                if (log != null) {
                    log.debug("[GTMemFix/VBOLeak] close() failed: {}", e.getMessage());
                }
            }
        }

        if (log != null && closed > 0) {
            log.debug("[GTMemFix/VBOLeak] closeAll: freed {} VBORenderType(s), {} remain",
                    closed, BY_NAME.size());
        }
    }
}
