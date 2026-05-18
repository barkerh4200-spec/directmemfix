package dev.gtpatch.mixin;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.client.renderer.MultiblockInWorldPreviewRenderer;
import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraftforge.fml.ModList;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(value = MultiblockInWorldPreviewRenderer.class, remap = false)
public abstract class MultiblockInWorldPreviewRendererMixin {

    private static final Logger LOGGER = LogManager.getLogger("gtpatch/MBIWPR");

    @Unique private static final AtomicBoolean STACK_LOGGED       = new AtomicBoolean(false);
    @Unique private static final AtomicInteger RENDER_PASS        = new AtomicInteger(0);
    @Unique private static final AtomicBoolean REPLAY_PRE_LOGGED  = new AtomicBoolean(false);
    @Unique private static final AtomicBoolean REPLAY_POST_LOGGED = new AtomicBoolean(false);
    @Unique private static final AtomicBoolean BEFORE_DRAW_LOGGED = new AtomicBoolean(false);
    @Unique private static final AtomicBoolean IRIS_DEFERRED_LOGGED    = new AtomicBoolean(false);
    @Unique private static final AtomicBoolean DEFERRED_STATE_LOGGED   = new AtomicBoolean(false);
    // null = not yet checked; Boolean.TRUE/FALSE = result cached
    @Unique private static final AtomicReference<Boolean> IRIS_ACTIVE_CACHE = new AtomicReference<>(null);

    // All uniform names that various shader packs use for the linear-fog start/end.
    @Unique private static final String[] FOG_START_NAMES =
        {"FogStart", "fogStart", "u_FogStart", "iris_FogStart"};
    @Unique private static final String[] FOG_END_NAMES =
        {"FogEnd",   "fogEnd",   "u_FogEnd",   "iris_FogEnd"};

    @Shadow(remap = false) private static TrackedDummyWorld LEVEL;
    @Shadow(remap = false) private static AtomicReference<Object> BUFFERS;

    // -------------------------------------------------------------------------
    // Bug 3 — cleanPreview teardown
    // -------------------------------------------------------------------------

    @Inject(method = "cleanPreview", at = @At("HEAD"), remap = false)
    private static void gtpatch_teardownBeforeClean(CallbackInfo ci) {
        LOGGER.info("[GTpatch/DIAG] cleanPreview() called. " +
                "LEVEL={}@{} thread={}",
                LEVEL == null ? "null" : LEVEL.getClass().getSimpleName(),
                LEVEL == null ? 0 : System.identityHashCode(LEVEL),
                Thread.currentThread().getName());

        if (LEVEL != null) {
            try { LEVEL.clear(); } catch (Exception ignored) {}
        }

        if (BUFFERS != null) {
            Object raw = BUFFERS.get();
            if (raw instanceof VertexBuffer[] buffers) {
                for (VertexBuffer vbo : buffers) {
                    if (vbo != null) {
                        try { vbo.close(); } catch (Exception ignored) {}
                    }
                }
                BUFFERS.set(null);
            }
        }

        try {
            dev.gtpatch.VBORenderTypeRegistry.closeAll(LOGGER);
        } catch (Exception ignored) {}

        REPLAY_PRE_LOGGED.set(false);
        REPLAY_POST_LOGGED.set(false);
        BEFORE_DRAW_LOGGED.set(false);
        DEFERRED_STATE_LOGGED.set(false);
    }

    // -------------------------------------------------------------------------
    // Iris/Oculus deferred-pipeline fix
    //
    // GTCEu fires renderInWorldPreview at RenderLevelStageEvent.AFTER_BLOCK_ENTITIES,
    // which lands inside Iris's GBuffer pass.  CosmicBliss's composite shader then
    // processes that GBuffer data and produces sky-coloured output regardless of
    // what the FallbackShader wrote.
    //
    // Fix: when Iris/Oculus is loaded, cancel the AFTER_BLOCK_ENTITIES call and
    // re-fire it at AFTER_LEVEL (handled by GTpatch.onRenderLevelStage), which
    // fires AFTER Iris has composited the scene — rendering goes directly to the
    // final framebuffer, bypassing CosmicBliss's deferred pipeline entirely.
    // -------------------------------------------------------------------------

    @Inject(
        method = "renderInWorldPreview(Lcom/mojang/blaze3d/vertex/PoseStack;"
               + "Lnet/minecraft/client/Camera;F)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void gtpatch_deferForIris(
            PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
        if (dev.gtpatch.GTpatch.IS_DEFERRED_CALL) return; // our own re-fire — let it proceed
        if (!gtpatch_isIrisActive()) return;              // no Iris — proceed normally

        if (IRIS_DEFERRED_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[GTpatch/IRIS] Iris/Oculus detected — deferring preview render");
        }

        // Capture the EXACT matrices GTCEu would use here.  This is GTCEu's real
        // call site (RenderLevelStageEvent.AFTER_BLOCK_ENTITIES); the poseStack
        // param is the camera-rotation modelview and RenderSystem's projection is
        // the world perspective — exactly what works in the non-Oculus case.
        // Snapshotting later (AFTER_LEVEL) catches an Oculus-corrupted pose stack.
        dev.gtpatch.GTpatch.DEFERRED_VIEW_MATRIX =
                new Matrix4f(poseStack.last().pose());
        dev.gtpatch.GTpatch.DEFERRED_PROJ_MATRIX =
                new Matrix4f(RenderSystem.getProjectionMatrix());
        dev.gtpatch.GTpatch.DEFERRED_CAMERA        = camera;
        dev.gtpatch.GTpatch.DEFERRED_PARTIAL_TICKS = partialTicks;
        dev.gtpatch.GTpatch.DEFERRED_PENDING       = true;

        if (DEFERRED_STATE_LOGGED.compareAndSet(false, true)) {
            Matrix4f v = dev.gtpatch.GTpatch.DEFERRED_VIEW_MATRIX;
            LOGGER.warn("[GTpatch/CAPTURE] view = [{} {} {} {} / {} {} {} {} / {} {} {} {} / {} {} {} {}]",
                v.m00(), v.m01(), v.m02(), v.m03(), v.m10(), v.m11(), v.m12(), v.m13(),
                v.m20(), v.m21(), v.m22(), v.m23(), v.m30(), v.m31(), v.m32(), v.m33());
            Matrix4f p = dev.gtpatch.GTpatch.DEFERRED_PROJ_MATRIX;
            LOGGER.warn("[GTpatch/CAPTURE] proj = [{} {} {} {} / {} {} {} {} / {} {} {} {} / {} {} {} {}]",
                p.m00(), p.m01(), p.m02(), p.m03(), p.m10(), p.m11(), p.m12(), p.m13(),
                p.m20(), p.m21(), p.m22(), p.m23(), p.m30(), p.m31(), p.m32(), p.m33());
        }
        ci.cancel();
    }

    @Unique
    private static boolean gtpatch_isIrisActive() {
        Boolean cached = IRIS_ACTIVE_CACHE.get();
        if (cached != null) return cached;
        boolean hasIris = ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
        if (IRIS_ACTIVE_CACHE.compareAndSet(null, hasIris)) {
            LOGGER.info("[GTpatch/IRIS] Iris/Oculus mod presence: {}", hasIris);
        }
        return IRIS_ACTIVE_CACHE.get();
    }

    // -------------------------------------------------------------------------
    // Bug 15 — fog fix, Part 1 (before apply)
    //
    // GTCEu sets FOG_START = Float.MAX_VALUE intending to disable fog, but with
    // fogEnd ≈ 256, the linear fog formula yields fogFactor ≈ 0 → all fog color
    // → sky-coloured silhouettes.  We pre-correct the Java-side uniform so that
    // vanilla ShaderInstance.apply() uploads the right value.
    // -------------------------------------------------------------------------

    @Inject(
        method = "renderInWorldPreview(Lcom/mojang/blaze3d/vertex/PoseStack;"
               + "Lnet/minecraft/client/Camera;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderInstance;m_173363_()V",
            remap = false
        ),
        remap = false
    )
    private static void gtpatch_fixFogBeforeApply(
            PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;

        // Set the Java-side uniform objects (used by vanilla apply() to upload).
        if (shader.FOG_START != null) shader.FOG_START.set(0f);
        if (shader.FOG_END   != null) shader.FOG_END.set(Float.MAX_VALUE);

        // Also set global RenderSystem fog state — Iris reads this when building
        // uniforms for its own gbuffer/composite programs at draw time.
        RenderSystem.setShaderFogStart(0f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        if (REPLAY_PRE_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("[GTpatch/REPLAY] pre-apply fog fix: shader={}  "
                    + "RenderSystem.fogStart→0 fogEnd→MAX",
                    shader.getClass().getName());
            for (int s = 0; s < 4; s++) {
                int id = RenderSystem.getShaderTexture(s);
                if (id != 0) LOGGER.warn("[GTpatch/REPLAY]   Sampler{} texId={}", s, id);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bug 15 — fog fix, Part 2 (after apply — GL direct override)
    //
    // Iris/Oculus's FallbackShader.apply() override re-sets fog uniforms from
    // Iris's own pipeline state, undoing our Part 1 fix.  We intercept AFTER
    // apply() has returned and write the uniforms directly into the currently
    // bound GL program by name, bypassing all Java wrapper layers.
    //
    // We also cover alternate names used by shader packs
    // (fogStart/u_FogStart/iris_FogStart) in case the pack doesn't use the
    // canonical vanilla names.
    // -------------------------------------------------------------------------

    @Inject(
        method = "renderInWorldPreview(Lcom/mojang/blaze3d/vertex/PoseStack;"
               + "Lnet/minecraft/client/Camera;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderInstance;m_173363_()V",
            shift = At.Shift.AFTER,
            remap = false
        ),
        remap = false
    )
    private static void gtpatch_fixFogAfterApply(
            PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {

        int prog = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        // Capture RenderSystem fog state BEFORE we touch it (shows what apply() left).
        float rsFogStartBefore = RenderSystem.getShaderFogStart();
        float rsFogEndBefore   = RenderSystem.getShaderFogEnd();

        if (prog == 0) {
            if (REPLAY_POST_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("[GTpatch/REPLAY2] prog=0 after apply() — GL fog fix skipped  "
                        + "RenderSystem.fogStart={} fogEnd={}", rsFogStartBefore, rsFogEndBefore);
            }
            return;
        }

        // Fix GL uniforms in the currently-bound program (prog=77 / FallbackShader).
        for (String name : FOG_START_NAMES) {
            int loc = GL20.glGetUniformLocation(prog, name);
            if (loc >= 0) GL20.glUniform1f(loc, 0.0f);
        }
        for (String name : FOG_END_NAMES) {
            int loc = GL20.glGetUniformLocation(prog, name);
            if (loc >= 0) GL20.glUniform1f(loc, Float.MAX_VALUE);
        }

        // Also correct RenderSystem fog state — Iris reads this at draw time when
        // setting up uniforms for its own gbuffer/composite programs.  apply() may
        // have already restored these from Iris's internal fog pipeline state, so
        // we override again here, after apply() has returned.
        RenderSystem.setShaderFogStart(0f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        if (REPLAY_POST_LOGGED.compareAndSet(false, true)) {
            StringBuilder sb = new StringBuilder("[GTpatch/REPLAY2] after-apply fog fix: prog=")
                    .append(prog)
                    .append("  RenderSystem.fogStart BEFORE=").append(rsFogStartBefore)
                    .append(" fogEnd BEFORE=").append(rsFogEndBefore).append("\n");
            for (String name : FOG_START_NAMES) {
                int loc = GL20.glGetUniformLocation(prog, name);
                sb.append("  FogStart '").append(name).append("' loc=").append(loc).append("\n");
            }
            for (String name : FOG_END_NAMES) {
                int loc = GL20.glGetUniformLocation(prog, name);
                sb.append("  FogEnd   '").append(name).append("' loc=").append(loc).append("\n");
            }
            LOGGER.warn(sb.toString().trim());
        }
    }

    // -------------------------------------------------------------------------
    // Bug 15 — fog fix, Part 3 (immediately before VertexBuffer.draw())
    //
    // Fires right before each VBO draw in the renderInWorldPreview loop.  By
    // this point Iris may have not yet switched programs, so setting the
    // RenderSystem fog state here is the last chance for Iris to read 0/MAX
    // when it builds its gbuffer-program uniforms at draw time.
    //
    // We also write to the currently-bound GL program's fog uniforms as a
    // belt-and-suspenders — in case Iris reads directly from the Java-side
    // RenderSystem state rather than sampling the active GL program.
    // -------------------------------------------------------------------------

    @Inject(
        method = "renderInWorldPreview(Lcom/mojang/blaze3d/vertex/PoseStack;"
               + "Lnet/minecraft/client/Camera;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;m_166882_()V",
            remap = false
        ),
        remap = false
    )
    private static void gtpatch_fixFogBeforeDraw(
            PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {

        int prog = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        float rsFogStart = RenderSystem.getShaderFogStart();
        float rsFogEnd   = RenderSystem.getShaderFogEnd();

        if (BEFORE_DRAW_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("[GTpatch/DRAWPRE] before draw: prog={}  "
                    + "RenderSystem.fogStart={} fogEnd={} deferred={}",
                    prog, rsFogStart, rsFogEnd, dev.gtpatch.GTpatch.IS_DEFERRED_CALL);
        }

        // Final fog correction: set both RenderSystem global state and GL uniforms.
        RenderSystem.setShaderFogStart(0f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        if (prog > 0) {
            for (String name : FOG_START_NAMES) {
                int loc = GL20.glGetUniformLocation(prog, name);
                if (loc >= 0) GL20.glUniform1f(loc, 0.0f);
            }
            for (String name : FOG_END_NAMES) {
                int loc = GL20.glGetUniformLocation(prog, name);
                if (loc >= 0) GL20.glUniform1f(loc, Float.MAX_VALUE);
            }
        }

        // NOTE: depth test is intentionally NOT disabled here anymore.
        // We render at RenderGuiEvent.Pre with a freshly-cleared depth buffer
        // (see GTpatch.onRenderGuiPre), so GTCEu's normal per-layer LEQUAL
        // depth test correctly self-occludes the preview blocks.  Forcibly
        // disabling GL_DEPTH_TEST here caused front blocks to be see-through.
    }

    // (Part 3b removed: nothing to restore now that Part 3 no longer disables
    //  the depth test — the preview self-occludes via the cleared depth buffer.)

    // -------------------------------------------------------------------------
    // Bug 14 — renderBlocks replacement (v27 approach + diagnostics)
    // -------------------------------------------------------------------------

    @Inject(
        method = "renderBlocks(Lcom/lowdragmc/lowdraglib/utils/TrackedDummyWorld;"
               + "Lcom/mojang/blaze3d/vertex/PoseStack;"
               + "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;"
               + "Lnet/minecraft/client/renderer/RenderType;"
               + "Lcom/lowdragmc/lowdraglib/client/scene/WorldSceneRenderer$VertexConsumerWrapper;"
               + "Ljava/util/Collection;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void gtpatch_replaceRenderBlocks(
            TrackedDummyWorld level,
            PoseStack poseStack,
            BlockRenderDispatcher dispatcher,
            RenderType layer,
            WorldSceneRenderer.VertexConsumerWrapper wrapperBuffer,
            Collection<BlockPos> renderedBlocks,
            CallbackInfo ci) {

        int pass = RENDER_PASS.incrementAndGet();

        LOGGER.warn("[GTpatch/DIAG] ═══ renderBlocks pass#{} ═══  layer={}  thread={}",
                pass, layer, Thread.currentThread().getName());
        LOGGER.warn("[GTpatch/DIAG]   param level  = {}@{}  (null={})",
                level  == null ? "null" : level.getClass().getSimpleName(),
                level  == null ? 0 : System.identityHashCode(level),
                level  == null);
        LOGGER.warn("[GTpatch/DIAG]   shadow LEVEL = {}@{}  (null={})",
                LEVEL  == null ? "null" : LEVEL.getClass().getSimpleName(),
                LEVEL  == null ? 0 : System.identityHashCode(LEVEL),
                LEVEL  == null);
        LOGGER.warn("[GTpatch/DIAG]   level == LEVEL: {}  renderedBlocks: {}  dispatcher: {}",
                level == LEVEL,
                renderedBlocks == null ? "NULL" : renderedBlocks.size(),
                dispatcher == null ? "NULL" : dispatcher.getClass().getSimpleName());

        if (STACK_LOGGED.compareAndSet(false, true)) {
            StringBuilder sb = new StringBuilder("[GTpatch/DIAG] Call stack (first pass):\n");
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                sb.append("    ").append(ste).append("\n");
            }
            LOGGER.warn(sb.toString());
        }

        if (level == null) {
            LOGGER.error("[GTpatch/DIAG] level param is NULL — aborting render pass");
            ci.cancel();
            return;
        }
        if (renderedBlocks == null || renderedBlocks.isEmpty()) {
            LOGGER.warn("[GTpatch/DIAG] renderedBlocks is null/empty — nothing to render");
            ci.cancel();
            return;
        }

        try {
            level.setRenderFilter(p -> true);
        } catch (Exception e) {
            LOGGER.error("[GTpatch/DIAG]   setRenderFilter FAILED: {}", e.toString());
        }

        int total = 0, success = 0, errors = 0, airSkipped = 0;

        for (BlockPos pos : renderedBlocks) {
            BlockState state = level.getBlockState(pos);
            FluidState fluidState = state.getFluidState();

            if (state.getBlock() == Blocks.AIR) {
                airSkipped++;
                continue;
            }

            if (state.getRenderShape() != RenderShape.INVISIBLE
                    && ItemBlockRenderTypes.getRenderLayers(state).contains(layer)) {

                total++;
                boolean verbose = (total <= 5);

                poseStack.pushPose();
                poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                poseStack.translate(0.5, 0.5, 0.5);
                poseStack.scale(0.8f, 0.8f, 0.8f);
                poseStack.translate(-0.5, -0.5, -0.5);

                try {
                    BakedModel model = dispatcher.getBlockModel(state);

                    if (verbose) {
                        ResourceLocation blockRl = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                        String modId = blockRl != null ? blockRl.getNamespace() : "unknown";
                        LOGGER.warn("[GTpatch/DIAG]   block[{}] pos={} block={} mod={}  model={}",
                                total, pos, blockRl, modId,
                                model == null ? "NULL" : model.getClass().getSimpleName());
                    }

                    if (model == null) {
                        LOGGER.error("[GTpatch/DIAG]   block[{}] model is NULL! skipping", total);
                        poseStack.popPose();
                        errors++;
                        continue;
                    }

                    BlockEntity be = level.getBlockEntity(pos);
                    ModelData md;
                    try {
                        md = model.getModelData(level, pos, state,
                                be != null ? be.getModelData() : ModelData.EMPTY);
                    } catch (Exception e) {
                        LOGGER.error("[GTpatch/DIAG]   block[{}] getModelData EXCEPTION: {}",
                                total, e.toString());
                        md = ModelData.EMPTY;
                    }

                    if (verbose) {
                        LOGGER.warn("[GTpatch/DIAG]   block[{}] modelData isEMPTY={}",
                                total, md == ModelData.EMPTY);
                        gtpatch_logSprites(total, model, state, md, layer);
                    }

                    dispatcher.renderBatched(state, pos, level, poseStack,
                            wrapperBuffer, false, GTValues.RNG, md, layer);
                    success++;

                } catch (Exception e) {
                    errors++;
                    LOGGER.error("[GTpatch/DIAG]   block[{}] renderBatched EXCEPTION: {}",
                            total, e.toString());
                    LOGGER.error("[GTpatch/DIAG]   block[{}] renderBatched trace:", total, e);
                }

                poseStack.popPose();
            }

            if (!fluidState.isEmpty()
                    && ItemBlockRenderTypes.getRenderLayer(fluidState) == layer) {
                wrapperBuffer.addOffset(
                        pos.getX() - (pos.getX() & 15),
                        pos.getY() - (pos.getY() & 15),
                        pos.getZ() - (pos.getZ() & 15));
                try {
                    dispatcher.renderLiquid(pos, level, wrapperBuffer, state, fluidState);
                } catch (Exception ignored) {}
            }

            wrapperBuffer.clerOffset();
            wrapperBuffer.clearColor();
        }

        LOGGER.warn("[GTpatch/DIAG] pass#{} layer={} DONE: total={} success={} errors={} airSkip={}",
                pass, layer, total, success, errors, airSkipped);

        ci.cancel();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Unique
    private static void gtpatch_logSprites(int blockIdx, BakedModel model,
            BlockState state, ModelData md, RenderType layer) {
        try {
            Direction[] dirs = new Direction[]{
                null,
                Direction.UP, Direction.DOWN,
                Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST
            };
            int quadCount = 0;
            StringBuilder spriteLog = new StringBuilder();
            for (Direction dir : dirs) {
                try {
                    List<BakedQuad> quads = model.getQuads(state, dir, GTValues.RNG, md, layer);
                    for (BakedQuad q : quads) {
                        quadCount++;
                        TextureAtlasSprite sprite = q.getSprite();
                        if (sprite == null) {
                            spriteLog.append("  face=").append(dir)
                                     .append(" quad#").append(quadCount)
                                     .append(" sprite=NULL\n");
                        } else {
                            String spriteName;
                            try { spriteName = sprite.contents().name().toString(); }
                            catch (Exception ex) { spriteName = sprite.getClass().getSimpleName() + "<??>"; }
                            spriteLog.append("  face=").append(dir)
                                     .append(" sprite=").append(spriteName)
                                     .append(" u0=").append(String.format("%.4f", sprite.getU0()))
                                     .append("\n");
                        }
                        if (quadCount >= 8) break;
                    }
                } catch (Exception e) {
                    spriteLog.append("  face=").append(dir)
                             .append(" getQuads EXCEPTION: ").append(e).append("\n");
                }
                if (quadCount >= 8) break;
            }
            LOGGER.warn("[GTpatch/DIAG]   block[{}] quads(capped8)={}  {}",
                    blockIdx, quadCount, spriteLog.toString().trim());
        } catch (Exception e) {
            LOGGER.error("[GTpatch/DIAG]   block[{}] sprite-log EXCEPTION: {}", blockIdx, e.toString());
        }
    }
}
