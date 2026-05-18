package dev.gtpatch;

import com.gregtechceu.gtceu.client.renderer.MultiblockInWorldPreviewRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Minimal mod entrypoint.  Hosts the deferred-render mechanism that makes
 * GTCEu's multiblock in-world preview visible when Oculus/Iris is active.
 *
 * Matrix capture — done by MultiblockInWorldPreviewRendererMixin at GTCEu's
 *   real call site (renderInWorldPreview HEAD, fired from AFTER_BLOCK_ENTITIES).
 *   The poseStack param there is the camera-rotation modelview and
 *   RenderSystem's projection is the world perspective — exactly what GTCEu
 *   uses successfully without Oculus.  Snapshotting later (AFTER_LEVEL) caught
 *   an Oculus-corrupted pose stack, which is why prior attempts failed.
 *
 * AFTER_LEVEL — pure gate: confirms the world render finished this frame.
 *
 * RenderGuiEvent.Pre — replay: GameRenderer.renderLevel() has fully returned,
 *   Oculus composites are done and their output is in FBO=2 (the MC main
 *   render target).  We restore the captured projection, seed a fresh
 *   PoseStack with the captured modelview, and call renderInWorldPreview so it
 *   draws directly on top of the composited scene, before the HUD → visible.
 */
@Mod(GTpatch.MODID)
@Mod.EventBusSubscriber(modid = GTpatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class GTpatch {
    public static final String MODID = "gtpatch";
    private static final Logger LOGGER = LogManager.getLogger("gtpatch");

    // Set to true by the mixin when it defers a renderInWorldPreview call.
    public static volatile boolean DEFERRED_PENDING      = false;
    // Set to true by onRenderGuiPre just before re-firing renderInWorldPreview,
    // so the mixin's HEAD inject knows NOT to cancel it again.
    public static volatile boolean IS_DEFERRED_CALL      = false;
    // partial-ticks value saved from the deferred call
    public static volatile float   DEFERRED_PARTIAL_TICKS = 0f;

    // ── matrices captured at GTCEu's real call site (by the mixin), consumed
    //    at RenderGuiEvent.Pre ───────────────────────────────────────────────
    private static volatile boolean GUI_RENDER_PENDING = false;
    public static volatile Matrix4f DEFERRED_VIEW_MATRIX = null;
    public static volatile Matrix4f DEFERRED_PROJ_MATRIX = null;
    public static volatile Camera   DEFERRED_CAMERA      = null;

    // ── Stage 1: save state ─────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (!DEFERRED_PENDING) return;
        DEFERRED_PENDING = false;
        // Pure gate: the world render finished this frame, so it is now safe to
        // replay the preview at RenderGuiEvent.Pre.  Matrices were already
        // captured by the mixin at GTCEu's real call site.
        GUI_RENDER_PENDING = true;
    }

    // ── Stage 2: render after Oculus composites ─────────────────────────────────

    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        if (!GUI_RENDER_PENDING) return;
        GUI_RENDER_PENDING = false;
        if (DEFERRED_VIEW_MATRIX == null || DEFERRED_PROJ_MATRIX == null
                || DEFERRED_CAMERA == null) return;
        IS_DEFERRED_CALL = true;

        // Snapshot the current (GUI ortho) projection matrix so we can restore it
        // afterwards, allowing normal HUD rendering to proceed.
        Matrix4f prevProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        try {
            // Restore the world perspective projection GTCEu read at its real
            // call site.  Oculus composite leaves an ortho matrix here, which
            // would z-clip all world-space blocks.
            RenderSystem.setProjectionMatrix(DEFERRED_PROJ_MATRIX, VertexSorting.DISTANCE_TO_ORIGIN);

            // Bind the main render target so blocks write to the final framebuffer.
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
            GL11.glColorMask(true, true, true, true);

            // Give the preview a pristine depth buffer.  Color is untouched, so
            // the composited world the player sees stays intact; only depth is
            // reset, so the ghost preview self-occludes correctly via GTCEu's
            // normal LEQUAL depth test and isn't clipped by stale Iris/world
            // depth.  (Vanilla also clears depth between the world and the GUI.)
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            // Fresh PoseStack seeded with the camera-rotation modelview captured
            // at GTCEu's real call site.  renderInWorldPreview pushes it and adds
            // translate(-camera.pos) itself, reproducing the exact transform that
            // works in the non-Oculus case.
            PoseStack renderPoseStack = new PoseStack();
            renderPoseStack.last().pose().set(DEFERRED_VIEW_MATRIX);
            // (one-shot capture diagnostics are logged by the mixin instead)
            MultiblockInWorldPreviewRenderer.renderInWorldPreview(
                    renderPoseStack, DEFERRED_CAMERA, DEFERRED_PARTIAL_TICKS);
        } finally {
            IS_DEFERRED_CALL = false;
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            // Restore GUI ortho projection for subsequent HUD rendering.
            RenderSystem.setProjectionMatrix(prevProj, VertexSorting.DISTANCE_TO_ORIGIN);
            // Release frame references to avoid holding stale objects.
            DEFERRED_VIEW_MATRIX = null;
            DEFERRED_PROJ_MATRIX = null;
            DEFERRED_CAMERA      = null;
        }
    }
}
