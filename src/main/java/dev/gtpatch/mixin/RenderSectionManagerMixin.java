package dev.gtpatch.mixin;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FIX — Bug 5 (Embeddium): {@code ClonedChunkSectionCache} retains live
 * {@link net.minecraft.world.level.chunk.LevelChunk} references after a chunk unloads
 *
 * <h3>Root cause</h3>
 * Embeddium's chunk build pipeline clones section data off the main thread so worker
 * threads can mesh chunks without holding the world lock.  It caches these clones in
 * {@link ClonedChunkSectionCache}: a {@code Long2ReferenceLinkedOpenHashMap} keyed on
 * section position (packed x/y/z).
 *
 * <p>Each {@link me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSection}
 * entry stores a strong reference to the original {@link net.minecraft.world.level.chunk.LevelChunk}
 * plus the entire {@link net.minecraft.world.level.chunk.LevelChunkSection} it was
 * cloned from.  When a chunk is unloaded by Minecraft, Forge fires the unload event and
 * Embeddium's {@link me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker}
 * calls {@code RenderSectionManager.onChunkRemoved(int chunkX, int chunkZ)}.
 *
 * <p>That method removes the {@link me.jellysquid.mods.sodium.client.render.chunk.RenderSection}
 * objects for the chunk from the render graph, but it <em>never</em> calls
 * {@link ClonedChunkSectionCache#invalidate(int, int, int)} for any of the chunk's
 * sections.  The cache entries for those sections remain live, each holding a strong
 * reference to the now-unloaded {@code LevelChunk}.  Minecraft cannot garbage-collect
 * the chunk, its tile entities, or any capability data attached to it.
 *
 * <p>The {@code cleanup()} method on the cache evicts entries by age and size, but it
 * runs on the update tick only when the renderer is active.  In a modpack with GTCEu
 * that generates enormous bases across many chunks this deferred eviction is far too
 * slow; stale {@code LevelChunk} objects accumulate until the heap fills.
 *
 * <h3>Fix</h3>
 * Inject at RETURN of {@code onChunkRemoved(int chunkX, int chunkZ)}.  For every section
 * Y index in the world's section range call
 * {@link ClonedChunkSectionCache#invalidate(int, int, int)}.
 * {@code invalidate} simply does {@code map.remove(SectionPos.asLong(x, y, z))}, so
 * calling it for section positions that aren't in the cache is a harmless no-op.
 *
 * <p>This makes the cache eviction synchronous with the Minecraft chunk unload path,
 * matching the behaviour that Embeddium upstream added in later versions.
 *
 * <h3>Bytecode evidence</h3>
 * Confirmed via constant-pool and bytecode analysis of
 * {@code me/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager.class} from
 * {@code embeddium-0.3.31+mc1.20.1.jar}:
 * <pre>
 *   Field: ClonedChunkSectionCache sectionCache;     // Lme/jellysquid/.../ClonedChunkSectionCache;
 *   Field: ClientLevel world;                         // Lnet/minecraft/client/multiplayer/ClientLevel;
 *
 *   void onChunkRemoved(int chunkX, int chunkZ):
 *     // calls onSectionRemoved(chunkX, y, chunkZ) for each y
 *     // does NOT call sectionCache.invalidate(...)
 *
 *   // ClonedChunkSectionCache.invalidate(int x, int y, int z):
 *   //   this.positionToEntry.remove(SectionPos.asLong(x, y, z));
 * </pre>
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {

    /**
     * The ClonedChunkSectionCache holding cloned LevelChunk section references.
     * Field descriptor confirmed via javap:
     *   me/jellysquid/mods/sodium/client/world/cloned/ClonedChunkSectionCache sectionCache
     */
    @Shadow(remap = false)
    private ClonedChunkSectionCache sectionCache;

    /**
     * The ClientLevel this renderer is attached to.  Needed to determine the valid
     * section Y range (getMinSection() .. getMaxSection()).
     * Field descriptor confirmed via javap:
     *   net/minecraft/client/multiplayer/ClientLevel world
     */
    @Shadow(remap = false)
    private ClientLevel world;

    /**
     * After a chunk is removed from the render graph, eagerly evict every section of
     * that chunk from the ClonedChunkSectionCache.
     *
     * <p>We inject at RETURN so that Embeddium's own onSectionRemoved bookkeeping has
     * already completed.  The sectionCache and world fields are therefore guaranteed to
     * be in a consistent state at this point.
     *
     * <p>Defensive null checks on sectionCache and world ensure this is safe even if
     * Mixin fires during partial construction or during unit-test environments where the
     * renderer is torn down before a full world is assigned.
     */
    @Inject(
        method = "onChunkRemoved(II)V",
        at = @At("RETURN"),
        remap = false
    )
    private void gtpatch_evictCacheOnChunkUnload(int chunkX, int chunkZ, CallbackInfo ci) {
        if (this.sectionCache == null || this.world == null) {
            return;
        }
        try {
            // Iterate every section Y in the world's section coordinate range.
            // ClientLevel.getMinSection() / getMaxSection() map to the obfuscated
            // m_151560_() / m_151561_() confirmed via javap in RenderSectionManager:
            //   invokevirtual ClientLevel.m_151560_:()I   (getMinSection)
            //   invokevirtual ClientLevel.m_151561_:()I   (getMaxSection, exclusive upper bound)
            final int minY = this.world.getMinSection();
            final int maxY = this.world.getMaxSection();
            for (int sectionY = minY; sectionY < maxY; sectionY++) {
                // invalidate(x, y, z) = positionToEntry.remove(SectionPos.asLong(x, y, z))
                // No-op if the entry is not present — safe to call unconditionally.
                this.sectionCache.invalidate(chunkX, sectionY, chunkZ);
            }
        } catch (Exception ignored) {
            // Never let our cleanup abort Embeddium's own rendering logic.
        }
    }
}
