package dev.gtpatch.mixin;

import com.gregtechceu.gtceu.api.gui.widget.PatternPreviewWidget;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * FIX -- Bug 4 (revised v5.3.1): GTCEu JEI multiblock preview goes blank after
 * clicking the layer up/down button, and stays blank for all subsequent previews.
 *
 * <h2>Root cause (bytecode-confirmed, gtceu-1.20.1-7.5.2.jar + ldlib-1.0.49.jar)</h2>
 *
 * The v5.2.0 fix incorrectly called {@code LEVEL.clear()} inside a {@code setPage}
 * injection. This broke the preview because:
 *
 * <ol>
 *   <li>{@code initializePattern()} is cached in a static {@code CACHE} map and runs
 *       exactly once per definition per JVM session. It is the ONLY place that calls
 *       {@code LEVEL.addBlocks(pattern.blockMap)}, populating
 *       {@code TrackedDummyWorld.renderedBlocks}.</li>
 *   <li>{@code setupScene(MBPattern)} -- called by both {@code setPage} and
 *       {@code updateLayer} -- only calls {@code sceneWidget.setRenderedCore(...)}.
 *       It does NOT re-populate LEVEL. Layer filtering is handled by the SceneWidget
 *       wrapper's {@code renderFilter} predicate ({@code pos -> core.contains(pos)}),
 *       not by LEVEL content.</li>
 *   <li>Once {@code LEVEL.clear()} wiped {@code renderedBlocks}, every
 *       {@code getBlockState(pos)} returned AIR (LEVEL is a no-arg
 *       {@code TrackedDummyWorld} -- {@code proxyWorld} is null -- so it reads
 *       solely from {@code renderedBlocks}). The VBO cache compiled to zero vertices.
 *       The preview stayed blank for the rest of the session because
 *       {@code initializePattern} never re-ran (CACHE hit).</li>
 * </ol>
 *
 * <h2>Key bytecode evidence</h2>
 * <pre>
 *   // updateLayer() offset 92 -- layer button handler:
 *   invokevirtual setupScene:(Lcom/.../MBPattern;)V
 *   // setPage(I) offset 33 -- page navigation:
 *   invokevirtual setupScene:(Lcom/.../MBPattern;)V
 *
 *   // setupScene -- does NOT touch LEVEL, only updates sceneWidget.core Set:
 *   sceneWidget.setRenderedCore(filtered.toList(), null);
 *
 *   // TrackedDummyWorld.getBlockState(pos) (m_8055_):
 *   Level proxy = proxyWorld.get();   // null for no-arg ctor
 *   if (proxy == null) return renderedBlocks.getOrDefault(pos, EMPTY).getBlockState();
 *   // => empty renderedBlocks => every pos returns AIR => blank VBO compile
 *
 *   // TrackedDummyWorld.addBlock(pos, info):
 *   renderedBlocks.put(pos, info);    // offset 14
 *   blockEntities.remove(pos);        // offset 26 -- DESTROYS block entity at pos!
 *   // => after addBlocks(blockMap), must restore controller BE via setInnerBlockEntity
 *
 *   // MBPattern fields -- public final in bytecode (flags 0x0011),
 *   //   but inaccessible to javac from outside package (inner class visibility).
 *   //   Accessed via reflection with setAccessible(true).
 *   Map&lt;BlockPos, BlockInfo&gt; blockMap;       // initializePattern populates LEVEL from this
 *   IMultiController          controllerBase; // null if no controller in this variant
 * </pre>
 *
 * <h2>Fix</h2>
 *
 * <p>Inject at HEAD of {@code setupScene(MBPattern)} -- the single call site for
 * both layer changes and page changes. Ensure LEVEL always has blocks before the
 * VBO compiler reads from it.
 *
 * <p><b>Guard:</b> {@code LEVEL.renderedBlocks.isEmpty()} -- the fast path (normal
 * layer click on a fresh widget) returns immediately with zero overhead.
 *
 * <p><b>Recovery:</b> when LEVEL is empty, call {@code LEVEL.addBlocks(blockMap)}
 * and {@code LEVEL.setInnerBlockEntity(controllerBE)} -- replicating exactly what
 * {@code initializePattern} does at offsets 192-222.
 *
 * <p><b>Reflection:</b> {@code MBPattern.blockMap} and {@code MBPattern.controllerBase}
 * are {@code public final} at the bytecode level but inaccessible to {@code javac}
 * when crossing package boundaries for inner classes. We use {@code setAccessible(true)}
 * to bypass this at runtime.
 *
 * <p>The v5.2.0 {@code setPage} inject that called {@code LEVEL.clear()} has been
 * REMOVED. It was the sole cause of the blank-screen bug.
 */
@Mixin(value = PatternPreviewWidget.class, remap = false)
public abstract class PatternPreviewWidgetMixin {

    private static final Logger LOGGER = LogManager.getLogger("GTpatch/PPWFix");

    /**
     * Reflected handle for {@code MBPattern.blockMap}.
     * Field is {@code public final Map<BlockPos, BlockInfo>} at the bytecode level
     * (flags 0x0011), but inaccessible to javac from outside the package due to
     * inner-class visibility rules. Initialised once on first inject call.
     */
    private static volatile Field f_blockMap = null;

    /**
     * Reflected handle for {@code MBPattern.controllerBase}.
     * Field is {@code public final IMultiController} at the bytecode level
     * (flags 0x0011). Same cross-package inner-class restriction as blockMap.
     */
    private static volatile Field f_controllerBase = null;

    /**
     * Shared static TrackedDummyWorld.
     * Confirmed: {@code private static TrackedDummyWorld LEVEL;} (flags 0x000a).
     */
    @Shadow(remap = false)
    private static TrackedDummyWorld LEVEL;

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    /**
     * Lazily initialise and cache the two {@code MBPattern} field handles.
     * Returns {@code true} if both handles are ready, {@code false} on any failure.
     */
    private static boolean initReflection(Object pattern) {
        if (f_blockMap != null && f_controllerBase != null) {
            return true;
        }
        try {
            Class<?> mbPatternClass = pattern.getClass();
            Field bm = mbPatternClass.getDeclaredField("blockMap");
            bm.setAccessible(true);
            Field cb = mbPatternClass.getDeclaredField("controllerBase");
            cb.setAccessible(true);
            f_blockMap = bm;
            f_controllerBase = cb;
            return true;
        } catch (Exception e) {
            LOGGER.warn("[GTpatch] Could not reflect MBPattern fields: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Injection
    // -------------------------------------------------------------------------

    /**
     * Ensure LEVEL has blocks before the VBO compiler reads from it.
     *
     * <p>Covers both call sites of {@code setupScene}:
     * <pre>
     *   setPage(I)V    -- offset 33: invokevirtual setupScene(patterns[page])
     *   updateLayer()V -- offset 92: invokevirtual setupScene(patterns[index])
     * </pre>
     *
     * <p>Fast path: {@code LEVEL.renderedBlocks} non-empty -- return immediately.
     * Recovery path: repopulate from {@code pattern.blockMap} via reflection and
     * restore the controller block entity.
     */
    @Inject(
        method = "setupScene(Lcom/gregtechceu/gtceu/api/gui/widget/PatternPreviewWidget$MBPattern;)V",
        at = @At("HEAD"),
        remap = false
    )
    private void gtpatch_ensureLevelPopulated(
            PatternPreviewWidget.MBPattern pattern,
            CallbackInfo ci) {

        if (LEVEL == null || pattern == null) {
            return;
        }

        // Fast path: LEVEL already has blocks -- layer filtering is done by the
        // SceneWidget core set, not by LEVEL content. Nothing to do.
        if (!LEVEL.renderedBlocks.isEmpty()) {
            return;
        }

        // Recovery path: LEVEL was cleared. Repopulate.
        if (!initReflection(pattern)) {
            return; // reflection setup failed, logged above -- don't abort setupScene
        }

        try {
            @SuppressWarnings("unchecked")
            Map<BlockPos, BlockInfo> blockMap =
                (Map<BlockPos, BlockInfo>) f_blockMap.get(pattern);

            if (blockMap == null || blockMap.isEmpty()) {
                return;
            }

            // Replicates initializePattern offset 192-196:
            //   LEVEL.addBlocks(blockMap);
            LEVEL.addBlocks(blockMap);

            // TrackedDummyWorld.addBlock() calls blockEntities.remove(pos) for every
            // position (offset 26 in addBlock bytecode), which just destroyed any
            // controller BE that was registered in LEVEL.blockEntities.
            // Restore it -- replicates initializePattern offsets 204-222.
            IMultiController controller =
                (IMultiController) f_controllerBase.get(pattern);

            if (controller != null) {
                try {
                    MultiblockControllerMachine machine = controller.self();
                    BlockEntity controllerBE = machine.holder.getSelf();
                    LEVEL.setInnerBlockEntity(controllerBE);
                } catch (Exception e) {
                    // Best-effort. Pattern still renders; formed-state BE is absent.
                    LOGGER.debug("[GTpatch] Controller BE restoration failed: {}",
                            e.getMessage());
                }
            }

            LOGGER.debug("[GTpatch] Restored {} blocks into LEVEL (was empty at setupScene)",
                    blockMap.size());

        } catch (Exception e) {
            // Never abort the original setupScene call.
            LOGGER.debug("[GTpatch] gtpatch_ensureLevelPopulated failed: {}",
                    e.getMessage());
        }
    }
}
