package dev.gtpatch.mixin;

import de.melanx.skyblockbuilder.SkyblockBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * FIX - Startup crash: NullPointerException in SkyblockBuilder.getLogger()
 *
 * Root cause:
 *   SkyblockBuilder.PackRepositoryMixin calls SkyblockBuilder.getLogger()
 *   from PackRepository.reloadPacks(), which is triggered by Minecraft.<init>()
 *   before Forge constructs the @Mod instance. getLogger() reads instance.logger
 *   and crashes because instance is still null.
 *
 * Compile constraints (confirmed via javap on SkyblockBuilder-1.20.1-5.1.28.jar):
 *   - instance   : private static  -> cannot access directly
 *   - getInstance(): public static  -> pulls in ModXRegistration (not on classpath)
 *   - getLogger() returns org.slf4j.Logger (not Log4j)
 *
 * Fix:
 *   Use reflection to read the private `instance` field at runtime only -- no
 *   direct field or method access at the call site, so no transitive deps are
 *   needed at compile time. If instance is null, cancel and return a SLF4J
 *   fallback logger so SkyblockBuilder's mixin can proceed safely.
 */
@Mixin(value = SkyblockBuilder.class, remap = false)
public abstract class SkyblockBuilderMixin {

    private static final Logger GTPATCH_FALLBACK =
        LoggerFactory.getLogger("SkyblockBuilder-fallback");

    @Inject(
        method = "getLogger()Lorg/slf4j/Logger;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void gtpatch_guardNullInstance(CallbackInfoReturnable<Logger> cir) {
        try {
            Field f = SkyblockBuilder.class.getDeclaredField("instance");
            f.setAccessible(true);
            if (f.get(null) == null) {
                cir.setReturnValue(GTPATCH_FALLBACK);
            }
        } catch (Exception ignored) {
            // If reflection fails for any reason, let the original method run.
        }
    }
}
