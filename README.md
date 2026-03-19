# gtpatch — GT Memory Fix v4.3.4

Fixes **seven** confirmed bugs in GregTech CEu Modern 7.5.2 + LDLib 1.0.49 +
Embeddium 0.3.31 + CodeChickenLib 4.4.0.516 on Forge 1.20.1.

---

## ⚠️ Required JVM Argument

For the direct buffer fixes to work correctly, you **must** add the following to
the **Additional Args** field in the CurseForge launcher settings for this modpack:

```
-Dio.netty.allocator.type=unpooled
```

**Where to add it:** CurseForge → My Modpacks → (right-click the pack) → Profile Options
→ Enable **Additional Java Arguments** → paste the argument above.

Without this, Netty's pooled memory allocator interferes with the direct buffer
accounting that the fix relies on.

---

## ⚠️ Required Dependency JARs

Before building, place the following JARs in the `libs/` folder. They are compile-time
dependencies only — the mod does not bundle them.

| Filename (exact)                                | Where to get it                                  |
|-------------------------------------------------|--------------------------------------------------|
| `forge-1.20.1-47.4.3-universal.jar`             | https://files.minecraftforge.net                 |
| `ldlib-forge-1.20.1-1.0.49.jar`                 | Modrinth / CurseForge — LDLib 1.0.49            |
| `gtceu-1.20.1-7.5.2.jar`                        | Modrinth / CurseForge — GregTech Modern 7.5.2   |
| `embeddium-0.3.31+mc1.20.1.jar`                 | Modrinth / CurseForge — Embeddium 0.3.31        |
| `CodeChickenLib-1.20.1-4.4.0.516-universal.jar` | Modrinth / CurseForge — CodeChickenLib 4.4.0.516|
| `BrandonsCore-1.20.1-3.2.1.302-universal.jar`   | Modrinth / CurseForge — BrandonsCore 3.2.1.302  |
| `Draconic-Evolution-1.20.1-3.1.2.621-universal.jar` | Modrinth / CurseForge — Draconic Evolution 3.1.2.621 |
| `Draconic-Additions-1.20.1-2.4.1.5-universal.jar`   | Modrinth / CurseForge — Draconic Additions 2.4.1.5  |
| `SkyblockBuilder-1.20.1-5.1.28.jar`             | Modrinth / CurseForge — SkyblockBuilder 5.1.28  |
| `client.jar` (Minecraft 1.20.1)                 | Extracted from your Minecraft installation       |

---

## Building

```
./gradlew build
```

Output: `build/libs/gtpatch-4.3.4.jar`

---

## Bugs Fixed

### Bug 3 — `MultiblockInWorldPreviewRenderer.cleanPreview()` (GTCEu)

**What breaks:** Every time a multiblock in-world preview is opened or changed, the
GTCEu renderer creates a new `TrackedDummyWorld` (`LEVEL`) populated with the
multiblock's blocks and block entities. When the preview is closed, `cleanPreview()`
sets `LEVEL = null` without first clearing its internal maps. This keeps every
`MetaMachineBlockEntity` for that preview (including all its traits, capability
`LazyOptional`s, and back-references into the real level) alive until the
`TrackedDummyWorld` itself is GC'd — which may never happen if a reference cycle
exists. The `BUFFERS` `VertexBuffer[]` array is also never closed, leaking OpenGL VBO
and VAO names on every preview change.

**Fix:** Inject at HEAD of `cleanPreview()` while `LEVEL` and `BUFFERS` are still
live. Call `LEVEL.clear()` to sever the TrackedDummyWorld's internal maps, then close
all `VertexBuffer` entries in `BUFFERS` and reset the `AtomicReference` to null so a
fresh array is allocated on the next session.

---

### Bug 4 — `PatternPreviewWidget.LEVEL` blanks out after layer button click (GTCEu / JEI)

**What breaks:** The JEI multiblock preview widget (`PatternPreviewWidget`) keeps a
single static `TrackedDummyWorld LEVEL` shared across all multiblock definitions.
`initializePattern()` populates it with block states and block entities, but it is
cached — it only runs **once** per multiblock definition per JVM session. An earlier
version of this fix (v5.2.0) called `LEVEL.clear()` inside a `setPage()` injection to
prevent block accumulation, but this made the preview go permanently blank: after
`clear()`, `getBlockState()` returns AIR for every position (LEVEL has no proxy world),
the VBO compiler produces zero vertices, and the preview stays dark for the rest of the
session because `initializePattern()` never re-runs. Clicking the layer up/down button
also triggered `setupScene()` which worsened the problem.

**Fix (v5.3.x):** The `setPage()` injection that called `LEVEL.clear()` has been
removed entirely. Instead, a single injection at HEAD of `setupScene(MBPattern)` — the
common call site for both page changes and layer changes — checks whether
`LEVEL.renderedBlocks` is empty. If it is (meaning LEVEL was cleared by any path), it
repopulates from `pattern.blockMap` and restores the controller block entity via
reflection. On normal layer clicks the fast path fires (non-empty → return immediately),
so there is zero overhead per button press.

---

### Bug 5 — `ClonedChunkSectionCache` / `LevelChunk` retention (Embeddium)

**What breaks:** When a chunk is unloaded, Embeddium's `RenderSectionManager` removes
render sections from the scene graph but never invalidates the corresponding entries
in `ClonedChunkSectionCache`. Each cached entry holds a strong reference to the
original `LevelChunk`. This prevents the chunk — and all attached GTCEu block entities,
traits, and capability data — from being garbage collected even after the server has
fully unloaded them, causing unbounded heap growth during long play sessions.

**Fix:** Inject at RETURN of `onChunkRemoved(II)V`. Iterate all section Y values from
`world.getMinSection()` to `world.getMaxSection()` and call
`sectionCache.invalidate(chunkX, y, chunkZ)` for each, mirroring what
`onSectionCompiled` does when sections are added.

---

### Bug 6 — `SinkingVertexBuilder.reallocDirect()` direct buffer growth (Embeddium)

**What breaks:** `SinkingVertexBuilder` is Embeddium's Forge block-rendering
compatibility adapter. It maintains an instance-field `ByteBuffer buffer` used as a
scratch area for vertex data emitted by `BakedModel.getQuads()`. There is one instance
per Embeddium worker thread. When the buffer needs to grow, `reallocDirect(old, newCapacity)`
allocates a new `ByteBuffer.allocateDirect(newCapacity)`, copies `old` into it, and
simply drops `old` without freeing it. `DirectByteBuffer` cleanup depends on the GC
running its `PhantomReference` cleaner queue. With dozens of GTCEu machines cycling
recipe state every few seconds — each triggering a full chunk-section rebuild —
discarded buffers accumulate faster than GC can reclaim them, causing direct memory
usage to grow at over 100 MB per minute and eventually OOM crashing the game.

**Fix:** Two coordinated injections on `reallocDirect(ByteBuffer old, int newCapacity)`:
1. `@Redirect` replaces `ByteBuffer.allocateDirect(newCapacity)` with
   `MemoryUtil.memAlloc(newCapacity)`, so the buffer is backed by `nmemAlloc`-owned
   native memory that can be freed deterministically.
2. `@Inject` at RETURN frees the old buffer immediately via `MemoryUtil.memFree(old)`
   once the data copy is complete. On the very first call `old` is the heap-allocated
   `EMPTY_BUFFER` — `memFree(0L)` is a C-standard no-op in that case.

---

### Bug 7 — `VBORenderType` VBO + VAO leak and direct buffer accumulation (CodeChickenLib / GTCEu)

**What breaks:** GTCEu's multiblock preview scene renderer creates a fresh set of
`VBORenderType` instances (from CodeChickenLib) every time a preview is opened or
rebuilt. Each `VBORenderType` allocates one OpenGL VBO and one VAO in its constructor,
plus a `BufferBuilder` whose backing direct `ByteBuffer` adds to native memory. When
GTCEu rebuilds the scene, the old `VBORenderType` instances are simply overwritten and
abandoned — `close()` is never called — so their GL names and direct buffers accumulate
permanently for the lifetime of the session. After one minute of gameplay the debug
report showed 1,520 live VBOs and 760 live VAOs, all originating from
`VBORenderType.<init>:25`.

**Fix — two-tier approach:**
- **Reactive tier:** Every `VBORenderType` constructed **off the render thread** is
  registered in `VBORenderTypeRegistry` keyed by its render type name (a stable,
  per-slot identifier). When a new instance is registered for a slot that already has
  one, the old instance is closed immediately — before the caller's constructor even
  returns. This fires for every GTCEu scene rebuild and keeps live VBOs bounded to
  exactly one per slot.
- **Safety-net tier:** At HEAD of `cleanPreview()`,
  `VBORenderTypeRegistry.closeAll()` drains any remaining instances that were never
  replaced (i.e., the last set created before the preview was closed).
- **Render-thread guard (v5.3.2):** Draconic Evolution tool renderers (pickaxe, axe,
  sword, etc.) also use `VBORenderType`, and every tool type shares the same render
  type name (`"draconicevolution:base"`). Without the guard, the reactive tier would
  close a tool's VBORenderType when another tool rendered, leaving a closed instance
  in the `LazyValue` that is never recreated — crashing with NPE in `rebuild()` when
  JEI tried to display the tool. The fix: skip registration for any `VBORenderType`
  constructed on the render thread. GTCEu's scene compiler runs on a background
  thread; DE item renderers run on the render thread. The guard cleanly separates them
  with zero impact on the leak fix.

---

### Bug (SkyblockBuilder) — `SkyblockBuilder.getLogger()` NPE on startup

**What breaks:** Forge's early loading pipeline calls `getLogger()` on the
`SkyblockBuilder` mod class before the `@Mod`-annotated instance has been created,
resulting in a `NullPointerException` during startup.

**Fix:** A reflection guard in `SkyblockBuilderMixin` checks for the null instance
before delegating to `getLogger()` and returns a fallback logger if the instance is
not yet ready.

---

## Changelog

| Version | Changes |
|---------|---------|
| 4.3.4 | Mirrored render-thread guard onto `close()` `freeDirectBuffer` path — fixes blank DE tool textures and "Error Rendering" in JEI on resource reload; fixed `@Shadow @Final` declaration |
| 4.3.3 | Added render-thread guard to `gtpatch_registerVBO` — fixes crash when viewing Draconic Evolution tool recipes in JEI |
| 4.3.2 | Rewrote Bug 4 fix — removed `setPage` / `LEVEL.clear()` injection that caused permanent blank preview; replaced with self-healing `setupScene` guard using reflection |
| 4.3.1 | Fixed build error in PatternPreviewWidgetMixin (cross-package inner class access) |
| 4.3.0 | Added Bug 7 `VBORenderType` VBO/VAO + direct buffer leak fix; removed false-positive Bug 1/2 injectors; added Bug 6 `SinkingVertexBuilder` direct buffer fix |
| 4.2.x | Added `ClonedChunkSectionCache` chunk retention fix (Bug 5) |
| 4.1.x | Initial release with Bugs 3 and 4, SkyblockBuilder NPE fix |
