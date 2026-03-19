package dev.gtpatch.mixin;

import codechicken.lib.render.buffer.VBORenderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.gtpatch.VBORenderTypeRegistry;
import io.netty.util.internal.PlatformDependent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * FIX — Bug 7 (CodeChickenLib 4.4.0.516): {@code VBORenderType} instances
 * created by GTCEu's multiblock preview scene renderer are never closed,
 * causing unbounded OpenGL VBO + VAO accumulation and direct ByteBuffer growth.
 *
 * <h3>Scope — render-thread guard (v4.3.3+)</h3>
 *
 * <p>This mixin applies to <em>all</em> {@code VBORenderType} instances, but
 * only the ones created <em>off</em> the render thread are tracked and managed
 * by {@link VBORenderTypeRegistry}. The guard fires in both the registration
 * path ({@link #gtpatch_registerVBO}) and the close path
 * ({@link #gtpatch_freeBuilderBufferOnClose}).
 *
 * <h4>Why the guard is required</h4>
 * <p>Every Draconic Evolution tool renderer ({@code RenderModularPickaxe},
 * {@code RenderModularAxe}, {@code RenderModularSword}, etc.) creates a
 * {@code VBORenderType} wrapping a {@code RenderType} named
 * {@code "draconicevolution:base"} — the same name across all tool types.
 * Without the guard, the reactive tier in {@link VBORenderTypeRegistry#register}
 * would close an earlier tool's VBORenderType the moment a later tool renders for
 * the first time. The closed instance is still held by a {@code LazyValue} that
 * never recreates it, causing a NPE in {@code rebuild()} on the next render.
 *
 * <p>The close-path guard mirrors the register guard: if {@code close()} is ever
 * called on a render-thread VBORenderType (for example, during a resource reload
 * when model renderers are rebuilt), we must not call
 * {@link PlatformDependent#freeDirectBuffer} on the backing {@code ByteBuffer}.
 * Freeing a buffer that is still in use by Embeddium's modified
 * {@code BufferBuilder} internals corrupts the builder's internal state —
 * causing blank/transparent item icons and {@code "Error Rendering"} in JEI.
 *
 * <h4>Thread ownership of GTCEu vs item renderer VBORenderTypes</h4>
 * <ul>
 *   <li><b>GTCEu WorldSceneRenderer</b> — compiles VBOs on a plain background
 *       {@link Thread} ({@code new Thread(lambda).start()} in
 *       {@code lambda$renderCacheBuffer$5}). Not the render thread.
 *       These ARE tracked and freed by our registry.</li>
 *   <li><b>DE / BrandonsCore item renderers</b> — called from
 *       {@code GuiGraphics.renderItem()} on the render thread.
 *       These are NOT tracked, and their buffers are NOT freed by us.</li>
 * </ul>
 *
 * <h3>@Shadow @Final — correct Mixin practice for final fields</h3>
 * <p>The {@code builder} field is {@code private final} in the target class
 * (confirmed access_flags 0x0012). In Mixin, shadowing a {@code final} field
 * requires the {@code @Final} annotation rather than the Java {@code final}
 * keyword in the shadow declaration. Using {@code @Shadow private final field = null}
 * without {@code @Final} causes the Java compiler to emit a
 * {@code putfield field, null} in the mixin's {@code <init>}, which Mixin must
 * strip. {@code @Final} explicitly signals to Mixin that the field is final in
 * the target, preventing any initializer handling issues and suppressing the
 * "non-final shadow" warning.
 */
@Mixin(value = VBORenderType.class, remap = false)
public abstract class VBORenderTypeLeakMixin {

    /**
     * Read-only shadow of the {@code private final BufferBuilder builder} field.
     *
     * <p>{@code @Final} is required (not {@code final} keyword) when shadowing
     * a final field in Mixin. Without it the Java compiler emits a
     * {@code putfield builder = null} in the mixin constructor that Mixin must
     * strip; omitting {@code @Final} risks that initializer being applied to
     * the target class instance, nulling the field after construction.
     *
     * <p>Confirmed private final (access_flags = 0x0012) via javap on
     * {@code CodeChickenLib-1.20.1-4.4.0.516-universal.jar}.
     */
    @Shadow @Final
    private BufferBuilder builder;

    /**
     * Cached reflection Field for {@code BufferBuilder.f_85648_} (the backing
     * {@code ByteBuffer}). Initialised lazily on first use and reused thereafter.
     * {@code volatile} for safe publication across the background compile thread
     * and the render thread.
     */
    private static volatile Field gtpatch_bbBufferField = null;

    // -------------------------------------------------------------------------
    // Registration — background-thread VBORenderTypes only
    // -------------------------------------------------------------------------

    /**
     * Register this instance in the global leak tracker at the end of construction.
     *
     * <p><b>Render-thread guard:</b> skip registration if the current thread IS
     * the render thread. All item renderers (DE tools, BrandonsCore contributor
     * wings, DE chestpiece armor parts) create VBORenderTypes on the render thread.
     * Registering them would cause the reactive tier to close them when another
     * tool with the same render-type name is rendered, leaving a closed instance
     * in the {@code LazyValue} — crashing or silently corrupting subsequent renders.
     */
    @Inject(
        method = "<init>(Lnet/minecraft/client/renderer/RenderType;Ljava/util/function/BiConsumer;)V",
        at = @At("RETURN"),
        remap = false
    )
    private void gtpatch_registerVBO(CallbackInfo ci) {
        if (RenderSystem.isOnRenderThread()) {
            return;
        }
        VBORenderTypeRegistry.register((VBORenderType) (Object) this);
    }

    // -------------------------------------------------------------------------
    // Cleanup — mirrors the same render-thread scope as registration
    // -------------------------------------------------------------------------

    /**
     * Unregister from the tracker and eagerly free the {@code BufferBuilder}'s
     * backing direct {@code ByteBuffer} when this instance is closed.
     *
     * <p><b>Render-thread guard on freeDirectBuffer:</b> if the current thread
     * IS the render thread, we skip the buffer free entirely. This covers the
     * case where {@code close()} is called on a render-thread VBORenderType —
     * for example during a resource reload when model renderers are rebuilt.
     * Calling {@code PlatformDependent.freeDirectBuffer} on a buffer that
     * Embeddium's modified {@code BufferBuilder} still tracks internally causes
     * corrupted builder state on subsequent renders, manifesting as blank item
     * textures and {@code "Error Rendering"} errors in JEI.
     *
     * <p>The {@code unregister()} call is intentionally placed before the thread
     * check: render-thread VBORenderTypes are never registered (see
     * {@link #gtpatch_registerVBO}), so {@code unregister()} is a no-op
     * conditional-remove for them regardless. Placing it first keeps the flow
     * clean and ensures the registry is always consistent.
     */
    @Inject(
        method = "close()V",
        at = @At("HEAD"),
        remap = false
    )
    private void gtpatch_freeBuilderBufferOnClose(CallbackInfo ci) {
        // Always unregister first (no-op for render-thread VBOs that were never registered).
        VBORenderTypeRegistry.unregister((VBORenderType) (Object) this);

        // Skip buffer free for render-thread VBORenderTypes.
        // If close() is called on a DE/BrandonsCore VBO (e.g., resource reload),
        // freeing its ByteBuffer would corrupt Embeddium's BufferBuilder internal
        // state and cause blank textures / "Error Rendering" in JEI.
        if (RenderSystem.isOnRenderThread()) {
            return;
        }

        // Eagerly free the BufferBuilder's backing ByteBuffer for background-thread
        // VBORenderTypes (GTCEu scene VBOs). These were created on a background thread
        // and are closed by our registry — safe to free their buffers immediately.
        if (this.builder == null) return;

        try {
            ByteBuffer buf = null;

            // Attempt 1: Embeddium's ExtendedBufferBuilder interface.
            if (this.builder instanceof me.jellysquid.mods.sodium.client.render.vertex.buffer.ExtendedBufferBuilder ext) {
                buf = ext.sodium$getBuffer();
            }

            // Attempt 2: Reflection on SRG field name f_85648_.
            if (buf == null) {
                Field f = gtpatch_bbBufferField;
                if (f == null) {
                    try {
                        f = BufferBuilder.class.getDeclaredField("f_85648_");
                        f.setAccessible(true);
                        gtpatch_bbBufferField = f;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (f != null) {
                    Object val = f.get(this.builder);
                    if (val instanceof ByteBuffer bb) buf = bb;
                }
            }

            if (buf != null && buf.isDirect()) {
                PlatformDependent.freeDirectBuffer(buf);
            }
        } catch (Exception ignored) {
            // Never abort the original close() call.
        }
    }
}
