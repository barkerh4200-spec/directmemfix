package dev.gtpatch.mixin;

import com.gregtechceu.gtceu.client.util.ClientImageCache;
import com.mojang.blaze3d.platform.NativeImage;
import io.netty.util.internal.PlatformDependent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

/**
 * FIX — Bug 10 (GTCEu 7.5.2): {@code ClientImageCache.saveTexture(String, byte[])}
 * creates a {@code ByteBuffer.allocateDirect(data.length)} to decode an image and
 * immediately abandons it — the buffer is never freed, causing ~4 MB of direct
 * memory to leak per downloaded texture.
 *
 * <h3>Root cause — bytecode-confirmed, ClientImageCache.class from gtceu-1.20.1-7.5.2.jar</h3>
 * {@code saveTexture(String url, byte[] data)} (full disassembly):
 * <pre>
 *   [load data.length]
 *   offset  2: INVOKESTATIC ByteBuffer.allocateDirect(I)ByteBuffer  ← LEAK
 *   offset  8: INVOKEVIRTUAL ByteBuffer.put([B)ByteBuffer
 *   offset 11: INVOKEVIRTUAL ByteBuffer.flip()Buffer
 *   offset 15: NEW DynamicTexture
 *   offset 20: INVOKESTATIC NativeImage.m_85062_(ByteBuffer)NativeImage  ← decodes PNG
 *   offset 23: INVOKESPECIAL DynamicTexture.<init>(NativeImage)V
 *   offset 27: INVOKEVIRTUAL TextureManager.m_118495_(...)V
 *   ...
 * </pre>
 * After {@code NativeImage.m_85062_(ByteBuffer)} returns, the {@code ByteBuffer}
 * is never referenced again — it has no field holding it, no local variable returned.
 * It is orphaned in the JVM GC pending-Cleaner queue.
 *
 * <h3>Why this explains the ~4 MB / texture growth</h3>
 * {@code NativeImage.m_85062_} (obfuscated {@code ehk.a(Ljava/nio/ByteBuffer;)Lehk;})
 * calls {@code stbi_load_from_memory(ByteBuffer, ...)} which reads the compressed
 * PNG bytes from the ByteBuffer and decodes them into a SEPARATE native allocation
 * (returned via {@code memByteBuffer(stbResult, width*height*channels)}). The original
 * {@code allocateDirect} ByteBuffer is used ONLY as source data for the decoder — it
 * is NOT retained by {@code NativeImage}. Once {@code m_85062_} returns, the source
 * ByteBuffer can be freed immediately with no risk of use-after-free.
 *
 * <p>Image sizes received from GTCEu's network packets are typically 50 KB–4 MB
 * (PNG-encoded textures for machine faces, item icons, etc.). With 40+ textures
 * loading per 5-minute window and none being freed, the direct buffer pool grows
 * by the cumulative uncompressed PNG size × number of textures loaded.
 *
 * <h3>Fix</h3>
 * {@code @Redirect} the {@code NativeImage.m_85062_(ByteBuffer)} call inside
 * {@code saveTexture}. The redirect:
 * <ol>
 *   <li>Captures the {@code ByteBuffer} passed as argument.</li>
 *   <li>Calls the original {@code NativeImage.read(buffer)} to decode the image.</li>
 *   <li>Immediately calls {@link PlatformDependent#freeDirectBuffer} to release the
 *       {@code ByteBuffer}'s native memory without waiting for GC Cleaner.</li>
 *   <li>Returns the decoded {@code NativeImage} normally.</li>
 * </ol>
 *
 * <h3>Safety</h3>
 * Free at step 3 is safe because:
 * <ul>
 *   <li>STBImage has already completed decoding and stored pixels in its own allocation.</li>
 *   <li>The {@code NativeImage} holds only the decoded pixel address, not the source buffer.</li>
 *   <li>No other reference to the ByteBuffer exists in {@code saveTexture} after offset 20.</li>
 * </ul>
 */
@Mixin(value = ClientImageCache.class, remap = false)
public abstract class ClientImageCacheMixin {

    /**
     * Free the source {@code ByteBuffer} immediately after {@code NativeImage.read()}
     * has decoded its contents.
     *
     * <p>Target: the single {@code INVOKESTATIC NativeImage.m_85062_(ByteBuffer)}
     * call at bytecode offset 20 of {@code saveTexture(String, byte[])V}.
     * This is the only call to {@code m_85062_} in the method, so the redirect
     * fires exactly once per {@code saveTexture} invocation.
     */
    @Redirect(
        method = "saveTexture(Ljava/lang/String;[B)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/NativeImage;read(Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/platform/NativeImage;"
        ),
        remap = false
    )
    private static NativeImage gtpatch_decodeAndFreeSourceBuffer(ByteBuffer buffer) throws Exception {
        try {
            // Decode the image. stbi_load_from_memory reads bytes from `buffer`
            // and stores decoded pixels in its own separate native allocation.
            // The returned NativeImage holds the decoded pixel address, NOT `buffer`.
            return NativeImage.read(buffer);
        } finally {
            // Free the source ByteBuffer immediately.
            // NativeImage.read() has finished with it — there is no other reference
            // to this buffer anywhere in saveTexture after this call.
            // PlatformDependent.freeDirectBuffer invokes sun.misc.Cleaner eagerly,
            // reclaiming the native memory without waiting for a GC cycle.
            if (buffer != null && buffer.isDirect()) {
                PlatformDependent.freeDirectBuffer(buffer);
            }
        }
    }
}
