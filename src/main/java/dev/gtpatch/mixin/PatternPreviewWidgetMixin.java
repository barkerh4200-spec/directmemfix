package dev.gtpatch.mixin;

import com.gregtechceu.gtceu.api.gui.widget.PatternPreviewWidget;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FIX — Bug 4: Static {@code PatternPreviewWidget.LEVEL} never cleared between pattern loads
 *
 * <h3>Root cause</h3>
 * {@code PatternPreviewWidget} holds a single static {@link TrackedDummyWorld} called
 * {@code LEVEL} that is shared across every instance of the widget (every multiblock
 * machine definition in the JEI/REI/EMI multiblock preview). It is populated by
 * {@code SceneWidget.setRenderedCore()} when a new pattern is displayed, but
 * {@code clear()} is never called first. Every block from every previously viewed
 * pattern variant accumulates in the world's internal maps across the session.
 *
 * <p>A bloated {@code LEVEL} amplifies Bugs 1 and 2: more blocks in the world means
 * more vertices per VBO compile pass, meaning more {@link com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer}
 * allocations per machine update cycle. Even though each buffer is individually freed
 * by the Bug 1/2 fixes, the peak allocation per compile pass keeps rising — which is
 * why direct memory kept growing even after the v4.0.0 patch.
 *
 * <h3>Call-graph analysis</h3>
 * {@code setupScene(MBPattern)} is called from TWO sites (confirmed via javap):
 *
 * <pre>
 *   setPage(I)V       — user presses left/right arrow to change pattern variant
 *   updateLayer()V    — called when the layer slider changes; may fire frequently
 * </pre>
 *
 * {@code updateScreen()V} calls {@code setPage(0)} exactly ONCE per widget lifetime:
 * it is guarded by the {@code isLoaded} boolean which is set to {@code true}
 * immediately after, so subsequent ticks skip the call entirely.
 *
 * The bytecode of {@code setPage} shows that {@code this.index} is written at
 * bytecode offset 16 (AFTER our HEAD inject runs), so at the HEAD inject point
 * {@code this.index} still holds the PREVIOUS index value. This lets us compare
 * {@code page != this.index} to detect a genuine page change.
 *
 * <h3>Fix</h3>
 * Inject ONLY on {@code setPage(I)V} with the guard {@code page != this.index}.
 * The guard has two effects:
 * <ol>
 *   <li>Prevents clearing on the once-per-open {@code updateScreen → setPage(0)} call
 *       (which passes the same index that's already stored).</li>
 *   <li>Prevents any future caller from triggering a spurious clear.</li>
 * </ol>
 *
 * <p>We deliberately do NOT inject on {@code setupScene} — that would run on every
 * {@code updateLayer()} call which fires on layer-slider interaction, clearing the
 * world that is actively being rendered and breaking preview display.
 *
 * <h3>Bytecode evidence</h3>
 * Field confirmed static via javap on {@code gtceu-1.20.1-7.5.2.jar}:
 * <pre>
 *   private static TrackedDummyWorld LEVEL;          // acc_flags = 0x000a (private static)
 *   private int index;                               // acc_flags = 0x0002 (private instance)
 *
 *   void setPage(int page):
 *     offset  0: iload_1                             // page
 *     ...bounds checks...
 *     offset 16: putfield PatternPreviewWidget.index:I  // this.index = page  ← AFTER HEAD
 *     offset 33: invokevirtual setupScene(MBPattern)V
 * </pre>
 */
@Mixin(value = PatternPreviewWidget.class, remap = false)
public abstract class PatternPreviewWidgetMixin {

    /**
     * The static TrackedDummyWorld shared across all pattern preview instances.
     * Field descriptor and static modifier confirmed via javap on gtceu-1.20.1-7.5.2.jar:
     *   private static com.lowdragmc.lowdraglib.utils.TrackedDummyWorld LEVEL;
     *   access_flags = 0x000a (private | static)
     */
    @Shadow(remap = false)
    private static TrackedDummyWorld LEVEL;

    /**
     * The current page index. At the time our HEAD inject fires, this still holds
     * the PREVIOUS page (the field is written by the original method body at
     * bytecode offset 16, after our inject).
     * Confirmed instance field, access_flags = 0x0002 (private).
     */
    @Shadow(remap = false)
    private int index;

    /**
     * Clear the shared TrackedDummyWorld before a genuine page change.
     *
     * <p>The guard {@code page != this.index} ensures we only act when the displayed
     * pattern is actually changing. This avoids:
     * <ul>
     *   <li>The once-per-open {@code updateScreen() → setPage(this.index)} call that
     *       JEI/EMI/REI fires to sync the recipe view.</li>
     *   <li>Any other same-index re-entrancy.</li>
     * </ul>
     *
     * <p>We do NOT inject on {@code setupScene} — that method is also called by
     * {@code updateLayer()} on layer-slider changes, which must not clear the world
     * while it is actively being rendered.
     */
    @Inject(
        method = "setPage(I)V",
        at = @At("HEAD"),
        remap = false
    )
    private void gtpatch_clearLevelOnPageChange(int page, CallbackInfo ci) {
        // Only clear when the page actually changes. At HEAD, this.index still
        // holds the old value because the field write happens later in the body.
        if (page == this.index) {
            return;
        }
        if (LEVEL != null) {
            try {
                LEVEL.clear();
            } catch (Exception ignored) {
                // Never let cleanup abort the original setPage call.
            }
        }
    }
}
