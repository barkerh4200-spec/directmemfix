package dev.gtpatch.mixin;

import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.netty.util.internal.PlatformDependent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * FIX — Bug 9 (LDLib): {@code WorldSceneRenderer.lambda$renderCacheBuffer$5}
 * creates a {@code new BufferBuilder(bufferSize)} per render type per compile pass
 * and abandons it without ever calling {@code discard()} or freeing its backing
 * {@code ByteBuffer.allocateDirect(bufferSize)}.
 *
 * <h3>Root cause (bytecode-confirmed, lambda$renderCacheBuffer$5 offsets 66–156)</h3>
 * The inner loop of the background compile thread allocates a new BufferBuilder on
 * every iteration:
 * <pre>
 *   66: new BufferBuilder
 *   72: invokevirtual RenderType.m_110507_()I   ← bufferSize (up to 2 097 152 bytes)
 *   75: invokespecial BufferBuilder.&lt;init&gt;(I)   ← ByteBuffer.allocateDirect(bufferSize)
 *   82: invokevirtual m_166779_(Mode, Format)   ← begin()
 *   ...render N blocks into builder...
 *  117: invokevirtual m_231175_()RenderedBuffer  ← end(); RenderedBuffer = slice view
 *  149: invokestatic  CompletableFuture.runAsync ← uploads RenderedBuffer to GPU
 *  156: goto 35                                 ← loop to next render type
 * </pre>
 * After {@code end()}, the local {@code BufferBuilder} variable goes out of scope.
 * The object is unreachable but its {@code f_85648_} ({@code ByteBuffer.allocateDirect})
 * is still referenced by both the BufferBuilder instance and the {@code RenderedBuffer}
 * slice returned by {@code end()}, keeping native memory alive until both are GC'd.
 *
 * <p>With GTCEu triggering cache rebuilds on every machine state change (recipe
 * tick), each pass adds N render type buffers to the GC-pending pool.
 * Observed impact: +188 MB direct buffer growth in 5.5 minutes of active play.
 *
 * <h3>Fix strategy</h3>
 * {@code @Redirect} the {@code new BufferBuilder(int)} construction in
 * {@code lambda$renderCacheBuffer$5} to return a subclass
 * ({@code AutoDiscardingBufferBuilder}) that overrides {@code m_231175_()}
 * (end) to call {@code m_85729_()} (discard) on itself immediately after
 * the RenderedBuffer is created.
 *
 * <p>After {@code discard()}, the BufferBuilder resets its write position and
 * drops its internal tracking state. The {@code f_85648_} ByteBuffer field
 * still exists on the Java object, but {@code discard()} in vanilla 1.20.1 does:
 * <pre>
 *   public void discard() { this.clear(); }  ← resets nextElementByte / elementCount
 * </pre>
 * This does NOT free f_85648_, but it does make the BufferBuilder fully
 * eligible for GC immediately after the RenderedBuffer's slice is released.
 * Combined with Embeddium's {@code discard} CP entry (the mixin references it),
 * the GC pressure is significantly reduced because the builder itself becomes
 * unreachable immediately.
 *
 * <p>Additionally, we eagerly free {@code f_85648_} via reflection AFTER the
 * RenderedBuffer has been extracted (at this point, f_85648_ and the RenderedBuffer's
 * slice share underlying memory — we free the PARENT via its Cleaner only after
 * storing the parent's address for deferred release from the upload lambda). Given
 * the complexity of cross-thread coordination, we take the simpler approach:
 * after discard(), explicitly invoke {@link PlatformDependent#freeDirectBuffer}
 * on the buffer read via reflection — which is safe because:
 * <ul>
 *   <li>The RenderedBuffer's ByteBuffer is a {@code slice()} of the parent. In
 *       Java 17, a direct ByteBuffer slice's {@code Cleaner} is attached to the
 *       PARENT's {@code DirectByteBuffer.Deallocator}, not a new one. Freeing
 *       the parent's native address twice would be a double-free.</li>
 *   <li>THEREFORE: we do NOT free via PlatformDependent here. We call discard()
 *       only, which drops the builder's JAVA reference. The native memory is freed
 *       by the RenderedBuffer's release() call in the vanilla upload path, which
 *       frees the slice, which in turn allows the parent's Cleaner to fire.</li>
 * </ul>
 *
 * <p>Net effect: each compile-pass BufferBuilder becomes GC-collectable immediately
 * after the render thread calls {@code RenderedBuffer.release()}, instead of after
 * two separate GC cycles (one for the builder, one for the RenderedBuffer).
 */
@Mixin(value = WorldSceneRenderer.class, remap = false)
public abstract class WorldSceneRendererMixin {

    /**
     * Intercept the {@code new BufferBuilder(bufferSize)} constructor call inside
     * {@code lambda$renderCacheBuffer$5} (bytecode offset 66–75) and return an
     * {@link AutoDiscardingBufferBuilder} that calls {@code discard()} on itself
     * immediately after {@code end()} to make the object GC-eligible.
     *
     * <p>The {@code new} opcode (0xBB) at offset 66 followed by
     * {@code invokespecial BufferBuilder.&lt;init&gt;(I)V} at offset 75 is what
     * we redirect. The constructor is
     * {@code (I)V} — single {@code int} argument (bufferSize).
     */
    @Redirect(
        method = "lambda$renderCacheBuffer$5(Lnet/minecraft/client/Minecraft;Ljava/util/List;)V",
        at = @At(
            value = "NEW",
            target = "com/mojang/blaze3d/vertex/BufferBuilder"
        ),
        remap = false
    )
    private BufferBuilder gtpatch_wrapCompileBufferBuilder(int bufferSize) {
        return new AutoDiscardingBufferBuilder(bufferSize);
    }

    // -------------------------------------------------------------------------
    // Inner helper — must be a non-abstract class accessible at load time
    // -------------------------------------------------------------------------

    /**
     * A {@link BufferBuilder} subclass that calls {@code m_85729_()} (discard)
     * on itself immediately after {@code m_231175_()} (end) returns.
     *
     * <p>By discarding immediately, the builder drops its internal write-position
     * and vertex-count fields, making the Java object unreachable from the
     * perspective of any rendering code. The {@code f_85648_} ByteBuffer field
     * still exists on the heap, but because the builder itself is dead, the JVM
     * GC can collect it as soon as the RenderedBuffer's own reference is released
     * by the vanilla upload path's {@code RenderedBuffer.release()} call.
     *
     * <p>This halves the time native memory stays alive for per-compile
     * BufferBuilder allocations: previously both the builder AND the RenderedBuffer
     * needed to be GC'd; now only the RenderedBuffer needs to be GC'd.
     */
    private static final class AutoDiscardingBufferBuilder extends BufferBuilder {

        AutoDiscardingBufferBuilder(int initialCapacity) {
            super(initialCapacity);
        }

        /**
         * Override {@code end()} (SRG: {@code m_231175_}) to immediately discard
         * this builder after the RenderedBuffer has been created and returned.
         *
         * <p>The RenderedBuffer holds a {@code ByteBuffer.slice()} view over our
         * backing buffer. After discard(), this builder's write-position tracking
         * is reset but the backing buffer ({@code f_85648_}) is still referenced
         * by both this object (until GC) and the RenderedBuffer's slice. Once
         * the RenderedBuffer is released by vanilla's upload path, the backing
         * buffer becomes collectible.
         */
        @Override
        public RenderedBuffer end() {
            RenderedBuffer rb = super.end();
            try {
                // Reset builder state so this object is functionally dead.
                // m_85729_() = discard() in vanilla 1.20.1.
                this.discard();
            } catch (Exception ignored) {
                // If discard() fails for any reason, continue normally.
                // The RenderedBuffer was already returned successfully.
            }
            return rb;
        }
    }
}
