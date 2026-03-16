package dev.gtpatch.mixin;

import codechicken.lib.render.buffer.VBORenderType;
import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.gtpatch.VBORenderTypeRegistry;
import io.netty.util.internal.PlatformDependent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * FIX — Bug 7 (CodeChickenLib 4.4.0.516): {@code VBORenderType} instances
 * are never closed when a multiblock preview session is torn down, causing a
 * permanent accumulation of OpenGL VBO + VAO name leaks AND direct ByteBuffer
 * accumulation.
 *
 * <h3>Field finality (bytecode-confirmed)</h3>
 * All three resource-holding fields of {@code VBORenderType} are
 * {@code private final} (access_flags = 0x0012):
 * <pre>
 *   [0x0012] factory:      Ljava/util/function/BiConsumer;
 *   [0x0012] vertexBuffer: Lcom/mojang/blaze3d/vertex/VertexBuffer;
 *   [0x0012] builder:      Lcom/mojang/blaze3d/vertex/BufferBuilder;
 * </pre>
 * {@code @Shadow} on a {@code final} field is legal for <em>reading</em>.
 * Writing to it from outside {@code <init>} raises
 * {@code java.lang.IllegalAccessError: Update to non-static final field ...
 * attempted from a different method than the initializer method <init>}.
 * We therefore never assign to the shadow field.
 *
 * <h3>Two-part leak</h3>
 * <b>Part A — GL VBO + VAO:</b> freed by {@code vertexBuffer.close()} in the
 * original {@code VBORenderType.close()} body, which our inject leaves intact.
 *
 * <b>Part B — Direct ByteBuffer in {@code builder}:</b>
 * {@code BufferBuilder(int)} calls {@code ByteBuffer.allocateDirect(initialCapacity)}.
 * {@code VBORenderType.close()} never touches {@code builder}; the ByteBuffer
 * waits for GC + Cleaner. Under GC pressure (~4 MB per preview session accumulates
 * because GTCEu uses 6+ render types per preview rebuild).
 *
 * <h3>Fix</h3>
 * In {@code close()} HEAD inject, read {@code this.builder} via the shadow field
 * and free its backing {@code ByteBuffer} immediately via
 * {@link PlatformDependent#freeDirectBuffer(ByteBuffer)}.
 *
 * The ByteBuffer is obtained via two attempts in priority order:
 * <ol>
 *   <li>Cast {@code builder} to Embeddium's
 *       {@code ExtendedBufferBuilder} interface and call
 *       {@code sodium$getBuffer()} — returns the {@code f_85648_} field
 *       (confirmed via Embeddium's {@code BufferBuilderMixin} bytecode).</li>
 *   <li>Fall back to reflection on the SRG name {@code f_85648_} directly
 *       on the {@code BufferBuilder} instance.</li>
 * </ol>
 * We deliberately do <em>NOT</em> null {@code this.builder} after freeing:
 * {@code builder} is {@code final} and writing to it outside {@code <init>}
 * raises {@code IllegalAccessError} at the JVM level.
 */
@Mixin(value = VBORenderType.class, remap = false)
public abstract class VBORenderTypeLeakMixin {

    /**
     * Read-only shadow of the {@code private final BufferBuilder builder} field.
     * Used only for reading — we never assign to this shadow.
     * Confirmed private final (access_flags = 0x0012) via javap.
     */
    @Shadow(remap = false)
    private final BufferBuilder builder = null;

    /**
     * Cached reflection Field for {@code BufferBuilder.f_85648_} (the backing
     * {@code ByteBuffer}). Initialised lazily on first use and reused thereafter
     * to avoid repeated reflection lookups. {@code volatile} for visibility across
     * the compile thread and the render thread.
     */
    private static volatile Field gtpatch_bbBufferField = null;

    /**
     * Register this instance in the global leak tracker at the end of construction.
     *
     * <p>At {@code @At("RETURN")} of {@code <init>}, all fields (including
     * {@code vertexBuffer}, {@code builder}) have been assigned. Safe to register.
     */
    @Inject(
        method = "<init>(Lnet/minecraft/client/renderer/RenderType;Ljava/util/function/BiConsumer;)V",
        at = @At("RETURN"),
        remap = false
    )
    private void gtpatch_registerVBO(CallbackInfo ci) {
        VBORenderTypeRegistry.register((VBORenderType) (Object) this);
    }

    /**
     * Unregister from the tracker AND eagerly free the {@code BufferBuilder}'s
     * backing direct {@code ByteBuffer} when this instance is closed.
     *
     * <p>{@code @At("HEAD")} fires before the original body's
     * {@code getfield vertexBuffer; invokevirtual VertexBuffer.close()} at
     * bytecode offsets 1–4. Order between buffer free and GL resource free
     * does not matter — they are independent resources.
     *
     * <p>We do NOT write to {@code this.builder} — it is {@code final}.
     */
    @Inject(
        method = "close()V",
        at = @At("HEAD"),
        remap = false
    )
    private void gtpatch_freeBuilderBufferOnClose(CallbackInfo ci) {
        // (a) Remove from live registry before GL cleanup.
        VBORenderTypeRegistry.unregister((VBORenderType) (Object) this);

        // (b) Eagerly free the BufferBuilder's backing ByteBuffer.
        //     builder is private final — we can READ it via @Shadow but cannot
        //     write to it. We only need to read it here to extract the ByteBuffer.
        if (this.builder == null) return;

        try {
            ByteBuffer buf = null;

            // Attempt 1: Embeddium's ExtendedBufferBuilder interface.
            // sodium$getBuffer() returns the f_85648_ field directly.
            // Confirmed via Embeddium BufferBuilderMixin bytecode:
            //   sodium$getBuffer()Ljava/nio/ByteBuffer;: getfield f_85648_
            if (this.builder instanceof me.jellysquid.mods.sodium.client.render.vertex.buffer.ExtendedBufferBuilder ext) {
                buf = ext.sodium$getBuffer();
            }

            // Attempt 2: Reflection on the SRG field name f_85648_.
            // f_85648_ is the SRG-mapped name for BufferBuilder.buffer in 1.20.1,
            // confirmed via Embeddium's BufferBuilderMixin which shadows it.
            if (buf == null) {
                Field f = gtpatch_bbBufferField;
                if (f == null) {
                    try {
                        f = BufferBuilder.class.getDeclaredField("f_85648_");
                        f.setAccessible(true);
                        gtpatch_bbBufferField = f;
                    } catch (NoSuchFieldException ignored) {
                        // Field name may differ in non-SRG environments; skip.
                    }
                }
                if (f != null) {
                    Object val = f.get(this.builder);
                    if (val instanceof ByteBuffer bb) {
                        buf = bb;
                    }
                }
            }

            // Free if direct. PlatformDependent.freeDirectBuffer invokes the
            // sun.misc.Cleaner immediately, reclaiming native memory without
            // waiting for a GC cycle.
            if (buf != null && buf.isDirect()) {
                PlatformDependent.freeDirectBuffer(buf);
            }
        } catch (Exception ignored) {
            // If either path fails, the GC Cleaner handles it eventually.
            // Never abort the original close() call.
        }
    }
}
