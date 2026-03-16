package dev.gtpatch.mixin;

import com.gregtechceu.gtceu.client.renderer.MultiblockInWorldPreviewRenderer;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.gtpatch.VBORenderTypeRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReference;

/**
 * FIX — Bug 3: {@code MultiblockInWorldPreviewRenderer.cleanPreview()} does not
 * properly tear down {@code LEVEL} or {@code BUFFERS}, leaving live references that
 * prevent GC of {@code MetaMachineBlockEntity} objects and GPU memory.
 *
 * NOTE: Bug 2 "RenderedBuffer leak in lambda$prepareBuffers$0" was confirmed as a
 * FALSE POSITIVE. {@code VertexBuffer.upload()} already calls {@code release()} in a
 * try/finally block in the vanilla 1.20.1 bytecode (confirmed via javap on client.jar:
 * eim.a(eie$b)V calls eie$b.e()V twice — once in the try body, once in the finally).
 * The Bug2 injection has been removed. See SinkingVertexBuilderMixin for the actual
 * direct-buffer leak fix.
 *
 * <h3>Bug 3 root cause</h3>
 * {@code cleanPreview()} sets {@code LEVEL = null} without calling {@code LEVEL.clear()}
 * first. The {@link TrackedDummyWorld} internal maps ({@code blockEntities},
 * {@code renderedBlocks}) continue holding live {@code MetaMachineBlockEntity} objects —
 * each carrying traits, capability {@code LazyOptional}s, and back-references into the
 * real level — until the {@code TrackedDummyWorld} itself becomes collectible, which
 * may never happen if a reference cycle exists. The {@code BUFFERS} array is also never
 * closed or reset, leaking GL names and mapped memory on every preview change.
 *
 * <h3>Fix</h3>
 * Inject at HEAD of {@code cleanPreview()} while LEVEL and BUFFERS are still live:
 * <ol>
 *   <li>Call {@code LEVEL.clear()} to sever the TrackedDummyWorld's internal maps.</li>
 *   <li>Close all {@code VertexBuffer} objects in the BUFFERS array and null the
 *       {@code AtomicReference} so {@code getBUFFERS()} re-allocates a clean array
 *       on the next preview session.</li>
 * </ol>
 */
@Mixin(value = MultiblockInWorldPreviewRenderer.class, remap = false)
public abstract class MultiblockInWorldPreviewRendererMixin {

    private static final Logger LOGGER = LogManager.getLogger("GTMemFix/VBOLeak");

    @Shadow(remap = false)
    private static TrackedDummyWorld LEVEL;

    @Shadow(remap = false)
    private static AtomicReference<Object> BUFFERS;

    @Inject(
        method = "cleanPreview()V",
        at = @At("HEAD"),
        remap = false
    )
    private static void gtpatch_teardownBeforeClean(CallbackInfo ci) {
        // (a) Sever TrackedDummyWorld maps before LEVEL is nulled.
        if (LEVEL != null) {
            try {
                LEVEL.clear();
            } catch (Exception ignored) {}
        }

        // (b) Close VertexBuffers (STATIC usage, cache-compiled) and reset
        //     AtomicReference so prepareBuffers() allocates a fresh array on
        //     the next showPreview().
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

        // (c) Close all live VBORenderType instances (DYNAMIC usage) that were
        //     created for this preview session but never closed.
        //
        //     ROOT CAUSE (bytecode-confirmed, CCL VBORenderType.class):
        //     GTCEu's MultiblockInWorldPreviewRenderer creates new
        //     VBORenderType instances on every preview rebuild. Each
        //     VBORenderType allocates a VertexBuffer(DYNAMIC) + VAO in its
        //     constructor (offset 10 of <init>). When a rebuild replaces old
        //     VBORenderType instances in GTCEu's render-type map/list, the
        //     old instances are discarded without close() being called,
        //     leaving 1,520 live VBOs (760 VAOs) per the debug report.
        //
        //     VBORenderTypeLeakMixin registers every new VBORenderType in
        //     VBORenderTypeRegistry at <init> RETURN, and unregisters at
        //     close() HEAD. Calling closeAll() here drains all instances that
        //     were orphaned during the session.
        VBORenderTypeRegistry.closeAll(LOGGER);
    }
}
