package dev.gtpatch.mixin;

import com.lowdragmc.lowdraglib.client.scene.FBOWorldSceneRenderer;
import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FIX — Bug 8 (LDLib): {@code FBOWorldSceneRenderer.fbo} (a {@code RenderTarget}
 * holding a GL framebuffer + color texture + depth texture) is never released when
 * a {@code SceneWidget} replaces its renderer or is closed.
 *
 * <h3>Root cause — confirmed via bytecode analysis of ldlib-forge-1.20.1-1.0.49.jar</h3>
 *
 * <b>Path 1 — renderer replacement:</b>
 * {@code SceneWidget.createScene(Level, boolean)} at bytecode offsets 42–52 calls
 * {@code this.renderer.deleteCacheBuffer()} on the old renderer before replacing it.
 * {@code deleteCacheBuffer()} frees only the {@code VertexBuffer[]} cache array;
 * it does NOT call {@code FBOWorldSceneRenderer.releaseFBO()}.
 *
 * <pre>
 *   SceneWidget.createScene(Level, Z)V bytecode:
 *     offset 42: getfield renderer
 *     offset 45: branch 56 (skip if null)
 *     offset 49: getfield renderer
 *     offset 52: invokevirtual deleteCacheBuffer()   ← VBOs freed, FBO NOT freed
 *     offset 61: new FBOWorldSceneRenderer
 *     offset 75: invokespecial FBOWorldSceneRenderer.<init>(Level, I, I)V
 *     offset 78: putfield renderer                   ← old renderer discarded; fbo leaked
 * </pre>
 *
 * The new {@code FBOWorldSceneRenderer.<init>} at offset 22 calls
 * {@code setFBOSize(w, h)} which allocates a new {@link com.mojang.blaze3d.pipeline.MainTarget}.
 * {@code MainTarget} calls {@code createBuffers()} which invokes:
 * <ul>
 *   <li>{@code glGenTextures()} × 2 — color texture + depth texture</li>
 *   <li>{@code glGenFramebuffers()} × 1 — the FBO itself</li>
 * </ul>
 * None of these are freed when the old renderer is abandoned.
 *
 * <b>Path 2 — widget close:</b>
 * {@code SceneWidget.setGui(ModularUI)} at offset 30 calls
 * {@code ModularUI.registerCloseListener(Runnable)} passing {@code this::releaseCacheBuffer}
 * (confirmed: no {@code lambda$setGui$} synthetic method exists → it is a direct
 * method-reference invokedynamic). {@code releaseCacheBuffer()} calls only
 * {@code renderer.deleteCacheBuffer()} — again, no FBO release.
 *
 * <h3>FBOWorldSceneRenderer.releaseFBO() is null-safe (bytecode-confirmed)</h3>
 * <pre>
 *   releaseFBO()V:
 *     offset  1: getfield fbo
 *     offset  4: branch 14 (ifnull → jump to return)
 *     offset  8: getfield fbo
 *     offset 11: invokevirtual RenderTarget.m_83930_()V  ← destroyBuffers() — frees GL objects
 *     offset 16: putfield fbo = null
 *     offset 19: return
 * </pre>
 * Calling {@code releaseFBO()} when {@code fbo} is already {@code null} is a safe no-op.
 * Calling it before {@code deleteCacheBuffer()} is also safe: they operate on disjoint
 * GL object sets (FBO/textures vs VertexBuffers).
 *
 * <h3>Fix</h3>
 * Two complementary injections:
 * <ol>
 *   <li>{@code @Inject HEAD of createScene(Level, boolean)} — if the current renderer
 *       is an {@code FBOWorldSceneRenderer}, call {@code releaseFBO()} on it before the
 *       method body replaces {@code this.renderer}. At HEAD, {@code this.renderer} still
 *       points to the old instance (field write happens at offset 78, after our inject).</li>
 *
 *   <li>{@code @Inject HEAD of releaseCacheBuffer()} — same guard and call, covering
 *       the close-listener path and any future caller that invokes this method for cleanup.</li>
 * </ol>
 *
 * <h3>Observed impact</h3>
 * The debug report shows {@code Textures +alloc=9, +freed=0, Dlive=+9} per snapshot
 * interval. Each leaked {@code MainTarget} carries 2–3 GL texture names. This fix
 * prevents the corresponding accumulation.
 */
@Mixin(value = SceneWidget.class, remap = false)
public abstract class SceneWidgetFBOLeakMixin {

    /**
     * The current renderer held by the SceneWidget.
     * May be {@code null} before the scene is initialised, or an
     * {@code ImmediateWorldSceneRenderer} (no FBO) when running without FBO support.
     * Confirmed instance field, access_flags = 0x0004 (package-private), via javap.
     */
    @Shadow(remap = false)
    WorldSceneRenderer renderer;

    /**
     * Release the old renderer's FBO before {@code createScene} replaces it.
     *
     * <p>At {@code @At("HEAD")}, {@code this.renderer} still holds the old renderer
     * reference — the field write that overwrites it happens at bytecode offset 78
     * (after {@code new FBOWorldSceneRenderer.<init>} at 61–75). We therefore grab
     * and release it here, before any replacement occurs.
     *
     * <p>Guard: only act when the old renderer is an {@code FBOWorldSceneRenderer}.
     * {@code ImmediateWorldSceneRenderer} has no FBO and must not be touched here.
     * A null {@code renderer} (first call in constructor) safely fails the instanceof.
     */
    @Inject(
        method = "createScene(Lnet/minecraft/world/level/Level;Z)V",
        at = @At("HEAD"),
        remap = false
    )
    private void gtpatch_releaseFboBeforeCreate(Level level, boolean useFbo, CallbackInfo ci) {
        if (renderer instanceof FBOWorldSceneRenderer fboRenderer) {
            try {
                fboRenderer.releaseFBO();
            } catch (Exception ignored) {
                // Never abort the original createScene call.
            }
        }
    }

    /**
     * Release the renderer's FBO when the widget's cache buffer is released.
     *
     * <p>{@code releaseCacheBuffer()} is the single cleanup method for this widget.
     * It is called from two sites (bytecode-confirmed via javap on SceneWidget.class):
     * <ol>
     *   <li>{@code SceneWidget.setGui(ModularUI)} at offset 17, guarded by
     *       {@code isInitialized()}, when the GUI is opened.</li>
     *   <li>The close listener registered at {@code setGui} offset 30, which captures
     *       {@code this::releaseCacheBuffer} as a method-reference {@code Runnable}
     *       (no synthetic lambda method generated — it is an invokedynamic).</li>
     * </ol>
     *
     * <p>The {@code @At("HEAD")} inject fires before the existing
     * {@code deleteCacheBuffer()} call in the original method body, but since
     * {@code releaseFBO()} and {@code deleteCacheBuffer()} operate on disjoint
     * GL object sets, ordering between them does not matter.
     */
    @Inject(
        method = "releaseCacheBuffer()V",
        at = @At("HEAD"),
        remap = false
    )
    private void gtpatch_releaseFboOnCacheRelease(CallbackInfo ci) {
        if (renderer instanceof FBOWorldSceneRenderer fboRenderer) {
            try {
                fboRenderer.releaseFBO();
            } catch (Exception ignored) {}
        }
    }
}
