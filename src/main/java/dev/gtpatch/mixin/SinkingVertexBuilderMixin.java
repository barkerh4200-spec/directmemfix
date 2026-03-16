package dev.gtpatch.mixin;

import me.jellysquid.mods.sodium.client.compat.ccl.SinkingVertexBuilder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FIX — Bug 6 (Embeddium): {@code SinkingVertexBuilder.reallocDirect()} leaks
 * {@code DirectByteBuffer} objects on every chunk-section rebuild triggered by a
 * machine state change.
 *
 * <h3>Root cause</h3>
 * {@link SinkingVertexBuilder} is Embeddium's Forge rendering compatibility adapter. It
 * maintains an instance-field {@code ByteBuffer buffer} that it uses as a scratch area
 * for vertex data emitted by Forge's {@code IForgeBlockEntityRenderer} and
 * {@code BakedModel.getQuads()} pipeline. Each time the buffer is full it calls:
 *
 * <pre>
 *   static ByteBuffer reallocDirect(ByteBuffer old, int newCapacity) {
 *       ByteBuffer newBuf = ByteBuffer.allocateDirect(newCapacity)   // ← leak
 *                                      .order(ByteOrder.nativeOrder());
 *       int oldPos = old.position();
 *       old.rewind();
 *       newBuf.put(old);
 *       newBuf.position(oldPos);
 *       return newBuf;
 *   }
 * </pre>
 *
 * <p>The old buffer is simply overwritten in the field and dropped — no explicit free.
 * Java's {@code DirectByteBuffer} is freed when its {@code sun.misc.Cleaner} phantom
 * reference is collected, but the GC only processes that queue when it runs a full
 * cycle. With dozens of GTCEu machines cycling recipe start/stop every few seconds,
 * each triggering a chunk-section rebuild that calls {@code m_5752_} (endVertex) per
 * block quad, the buffers accumulate faster than the GC can reclaim them, causing the
 * JVM's direct-buffer pool to grow at &gt;100 MB/min.
 *
 * <h3>Scope of impact</h3>
 * Bytecode-confirmed: {@code SinkingVertexBuilder} is an instance field of
 * {@code BlockRenderer}, which is itself a field of the per-worker-thread
 * {@code ChunkBuildContext}. It is therefore allocated once per Embeddium worker thread
 * and lives for the thread's lifetime. The buffer starts small ({@code EMPTY_BUFFER},
 * a heap {@code ByteBuffer.allocate(0)}) and grows to the largest quad batch seen so
 * far. Each growth step leaks the old buffer.
 *
 * <h3>Fix</h3>
 * Two coordinated injections on {@code reallocDirect(ByteBuffer old, int newCapacity)}:
 *
 * <ol>
 *   <li><b>@Redirect</b> — replace {@code ByteBuffer.allocateDirect(newCapacity)} with
 *       {@code MemoryUtil.memAlloc(newCapacity)}. The resulting buffer is backed by
 *       {@code nmemAlloc}-owned native memory that can be deterministically freed with
 *       {@code MemoryUtil.memFree()}.</li>
 *
 *   <li><b>@Inject at RETURN</b> — after the old buffer's data has been fully copied
 *       into the new buffer, call {@code MemoryUtil.memFree(old)} to immediately release
 *       the old buffer's native memory. {@code memFree} is safe for both cases:
 *       <ul>
 *         <li>If {@code old} is the initial {@code EMPTY_BUFFER} (a heap
 *             {@code ByteBuffer.allocate(0)}), {@code memAddressSafe} returns 0 and
 *             {@code nmemFree(0)} is a POSIX-standard no-op.</li>
 *         <li>If {@code old} is a previous {@code MemoryUtil.memAlloc()} buffer,
 *             {@code memFree} returns it to the allocator immediately.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>The RETURN injection runs AFTER all reads from {@code old} have completed (the
 * {@code old.rewind(); newBuf.put(old)} copy finishes before RETURN), so this is not
 * a use-after-free.
 *
 * <p>The final active buffer (one per worker thread, at its stable maximum size) is
 * not freed on worker shutdown because {@code BlockRenderer} has no {@code destroy()}
 * method. This is an acceptable bounded leak: at most
 * {@code N_workerThreads × maxQuadBatchBytes}, which in practice is a few MB.
 *
 * <h3>Bytecode evidence</h3>
 * Confirmed via constant-pool and call-graph analysis of
 * {@code me/jellysquid/mods/sodium/client/compat/ccl/SinkingVertexBuilder.class}
 * from {@code embeddium-0.3.31+mc1.20.1.jar}:
 * <pre>
 *   instance field: buffer  Ljava/nio/ByteBuffer;
 *   static  field:  EMPTY_BUFFER  Ljava/nio/ByteBuffer;   (set via ByteBuffer.allocate())
 *
 *   static reallocDirect(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;:
 *     INVOKESTATIC  ByteBuffer.allocateDirect:(I)Ljava/nio/ByteBuffer;   ← redirected
 *     INVOKEVIRTUAL ByteBuffer.order:(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;
 *     INVOKEVIRTUAL ByteBuffer.position:()I
 *     INVOKEVIRTUAL ByteBuffer.rewind:()Ljava/nio/ByteBuffer;
 *     INVOKEVIRTUAL ByteBuffer.put:(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;
 *     ...
 *     ARETURN                                                             ← inject RETURN
 * </pre>
 *
 * {@code BlockRenderer.<init>} field: {@code sinkingVertexBuilder Lme/.../SinkingVertexBuilder;}
 * — one instance per {@code ChunkBuildContext} per worker thread.
 */
@Mixin(value = SinkingVertexBuilder.class, remap = false)
public abstract class SinkingVertexBuilderMixin {

    /**
     * Replace {@code ByteBuffer.allocateDirect(newCapacity)} with
     * {@code MemoryUtil.memAlloc(newCapacity)}.
     *
     * <p>{@code memAlloc} allocates via {@code nmemAlloc} — the native memory is
     * owned by LWJGL's allocator and can be freed deterministically with
     * {@code MemoryUtil.memFree()}, without waiting for GC to process a
     * {@code PhantomReference} queue.
     *
     * <p>The subsequent {@code .order(ByteOrder.nativeOrder())} call in the original
     * method body is a no-op since {@code memAlloc} already returns a buffer in the
     * platform's native byte order. All other operations ({@code .position()},
     * {@code .rewind()}, {@code .put()}) work identically on any {@code ByteBuffer}.
     */
    @Redirect(
        method = "reallocDirect(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;",
        at = @At(
            value = "INVOKE",
            target = "Ljava/nio/ByteBuffer;allocateDirect(I)Ljava/nio/ByteBuffer;"
        ),
        remap = false
    )
    private static ByteBuffer gtpatch_allocWithMemUtil(int capacity) {
        // Allocate via nmemAlloc so the buffer is deterministically freeable.
        // The .order() call that follows in the original code is a harmless no-op.
        return MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
    }

    /**
     * Free the old buffer immediately after its data has been fully copied to the
     * new one.
     *
     * <p>Injecting at RETURN guarantees:
     * <ul>
     *   <li>The {@code old.rewind(); newBuf.put(old)} copy has completed — no
     *       use-after-free.</li>
     *   <li>The new buffer has already been returned to the caller — the field
     *       {@code SinkingVertexBuilder.buffer} will be updated to the new value by
     *       the caller ({@code m_5752_}), not by us.</li>
     * </ul>
     *
     * <p>{@code MemoryUtil.memFree(old)} behaviour:
     * <ul>
     *   <li>If {@code old} is {@code EMPTY_BUFFER} ({@code ByteBuffer.allocate(0)},
     *       a heap buffer): {@code memAddressSafe} returns {@code 0L};
     *       {@code nmemFree(0L)} is a C standard no-op. Safe.</li>
     *   <li>If {@code old} is a previous {@code memAlloc()} buffer: freed
     *       immediately. Safe.</li>
     * </ul>
     */
    @Inject(
        method = "reallocDirect(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;",
        at = @At("RETURN"),
        remap = false
    )
    private static void gtpatch_freeOldBuffer(
            ByteBuffer old,
            int newCapacity,
            CallbackInfoReturnable<ByteBuffer> cir) {
        MemoryUtil.memFree(old);
    }
}
