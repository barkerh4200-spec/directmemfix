# gtpatch — GT Memory Fix v5.2.0

Fixes **five** confirmed leaks in GregTech CEu Modern 7.5.2 + LDLib 1.0.49 +
Embeddium 0.3.31 on Forge 1.20.1.


!!! for this to work properlly you have to add: 

-Dio.netty.allocator.type=unpooled

to the "Additional Args"

!!!

> **v5.2.0 changelog:** Removed false-positive Bug 1/2 `RenderedBuffer.release()`
> injectors (bytecode analysis of client.jar confirmed `VertexBuffer.upload()` already
> calls `release()` internally in a try/finally — they were silent no-ops). Added
> **Bug 6** fix — the actual source of the >100 MB/min direct buffer growth with
> machines running.

---

## libs/ — required JARs

| Filename (exact)                           | Where to get it                                       |
|--------------------------------------------|-------------------------------------------------------|
| `forge-1.20.1-47.4.3-universal.jar`        | https://files.minecraftforge.net                      |
| `ldlib-forge-1.20.1-1.0.49.jar`            | Modrinth / CurseForge — LDLib 1.0.49                 |
| `gtceu-1.20.1-7.5.2.jar`                   | Modrinth / CurseForge — GregTech Modern 7.5.2        |
| `embeddium-0.3.31+mc1.20.1.jar`            | Modrinth / CurseForge — Embeddium 0.3.31             |
| `SkyblockBuilder-1.20.1-5.1.28.jar`        | Modrinth / CurseForge — SkyblockBuilder 5.1.28       |

---

## Building

```
./gradlew build
```

Output: `build/libs/gtpatch-5.2.0.jar`

---

## What is fixed

### Bug 3 — `MultiblockInWorldPreviewRenderer.cleanPreview()` (GTCEu)

`cleanPreview()` sets `LEVEL = null` without calling `LEVEL.clear()`. The
`TrackedDummyWorld` internal maps keep live `MetaMachineBlockEntity` objects (traits,
capability `LazyOptional`s, back-refs to the real level) reachable until the world
itself is GC'd — which may never happen if a reference cycle exists. The `BUFFERS`
`VertexBuffer[]` array is also never closed, leaking GL names on every preview change.

**Fix:** inject at HEAD of `cleanPreview()`; call `LEVEL.clear()` then close and
null-out `BUFFERS`.

### Bug 4 — `PatternPreviewWidget.LEVEL` (GTCEu)

The static `TrackedDummyWorld LEVEL` shared across every JEI/REI/EMI pattern preview
is never cleared between pattern page changes. Every block from every previously viewed
pattern accumulates indefinitely. This also amplifies any rendering leaks because
more blocks = more vertices per compile pass.

**Fix:** inject at HEAD of `setPage(I)V` guarded by `page != this.index`. Calls
`LEVEL.clear()` only on genuine page changes, not the once-per-open sync call from
`updateScreen()`.

### Bug 5 — `ClonedChunkSectionCache` / `LevelChunk` retention (Embeddium)

`RenderSectionManager.onChunkRemoved(int, int)` removes render sections from the
graph but never calls `sectionCache.invalidate(x, y, z)` for those sections. Each
`ClonedChunkSection` entry holds a strong reference to the original `LevelChunk`,
preventing GC of unloaded chunks and all attached GTCEu block entity / capability data.

**Fix:** inject at RETURN of `onChunkRemoved(II)V`; iterate all section Y in
`world.getMinSection()..getMaxSection()` and call `sectionCache.invalidate(chunkX, y, chunkZ)`.

### Bug 6 — `SinkingVertexBuilder.reallocDirect()` (Embeddium) ← **THE REAL DIRECT BUFFER LEAK**

`SinkingVertexBuilder` is Embeddium's Forge block rendering compatibility adapter. It
holds an instance-field `ByteBuffer buffer` that it uses as a scratch area for vertex
data from Forge's `BakedModel.getQuads()` pipeline. It is one per Embeddium worker thread
(`BlockRenderer` → `ChunkBuildContext` → worker thread).

When the buffer needs to grow, `reallocDirect(old, newCapacity)` allocates a new
`ByteBuffer.allocateDirect(newCapacity)`, copies data from `old`, and drops `old`
without freeing it. `DirectByteBuffer` cleanup depends on GC running a
`PhantomReference` cleaner queue. With dozens of GTCEu machines cycling recipe state
every few seconds — each triggering a full chunk-section rebuild per machine — the old
buffers accumulate at >100 MB/min, far faster than GC can reclaim them.

**Bytecode-confirmed root cause:** `eim.a(Leie$b;)V` (`VertexBuffer.upload`) calls
`eie$b.e()V` (`RenderedBuffer.release()`) in a try/finally block — so the previously
patched "Bug 1/2" were false positives. `SinkingVertexBuilder.reallocDirect` is the
actual allocation site. Confirmed via constant-pool and call-graph analysis of
`embeddium-0.3.31+mc1.20.1.jar`.

**Fix:** two coordinated injections on `reallocDirect(ByteBuffer old, int newCapacity)`:
1. `@Redirect` — replace `ByteBuffer.allocateDirect(newCapacity)` with
   `MemoryUtil.memAlloc(newCapacity)` so the buffer is owned by `nmemAlloc` and can
   be deterministically freed.
2. `@Inject` at `RETURN` — after the copy from `old` is complete, call
   `MemoryUtil.memFree(old)` to release the old buffer immediately.
   - `old` = `EMPTY_BUFFER` (heap `ByteBuffer.allocate(0)`) on first call →
     `memFree(0L)` = C-standard no-op ✓
   - `old` = previous `memAlloc()` buffer on subsequent calls → freed immediately ✓

### Bug (SkyblockBuilder) — `SkyblockBuilder.getLogger()` NPE on startup

Forge's early loading calls `getLogger()` before the `@Mod` instance exists.
**Fix:** reflection guard in `SkyblockBuilderMixin`.
