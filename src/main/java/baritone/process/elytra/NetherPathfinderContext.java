/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.event.events.BlockChangeEvent;
import baritone.utils.accessor.IPalettedContainer;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author Brady
 */
public final class NetherPathfinderContext implements IElytraPathFinder {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    private static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState();
    // blocks per 16x16x16 section (replaces LevelChunkSection.SECTION_SIZE, removed from vanilla in 26.x)
    private static final int SECTION_SIZE = 16 * 16 * 16;
    // This lock must be held while there are active pointers to chunks in java,
    // but we just hold it for the entire tick so we don't have to think much about it.
    public final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    public final ReentrantReadWriteLock.ReadLock readLock = rwl.readLock();
    public final ReentrantReadWriteLock.WriteLock writeLock = rwl.writeLock();
    private final int maxHeight;

    // Visible for access in BlockStateOctreeInterface
    final long context;
    // Set under the write lock before freeContext; every native call checks it under the read lock
    // so nothing can touch the context pointer after it has been freed.
    private volatile boolean destroyed;
    private final long seed;
    // write locked operations
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    // operations that don't make changes to the chunk cache. could use multiple threads but i'm not sure if it would cause problems.
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final ResourceKey<Level> dimension;
    final int minY;
    private final BlockStateOctreeInterface boi;

    public NetherPathfinderContext(long seed, Path cache, Level world) {
        this.dimension = world.dimension();
        this.minY = world.dimensionType().minY();
        final int dim;
        if (this.dimension == Level.NETHER) dim = NetherPathfinder.DIMENSION_NETHER;
        else if (this.dimension == Level.END) dim = NetherPathfinder.DIMENSION_END;
        else dim = NetherPathfinder.DIMENSION_OVERWORLD;
        int height = Math.min(world.dimensionType().height(), 384);
        if (!Baritone.settings().elytraAllowAboveRoof.value && dim == NetherPathfinder.DIMENSION_NETHER) height = Math.min(height, 128);
        this.maxHeight = height;
        this.context = NetherPathfinder.newContext(seed, cache != null ? cache.toString() : null, dim, height, Baritone.settings().elytraCustomAllocator.value);
        this.seed = seed;
        this.boi = new BlockStateOctreeInterface(this);
    }

    public boolean hasChunk(ChunkPos pos) {
        readLock.lock();
        try {
            if (this.destroyed) {
                return false;
            }
            return NetherPathfinder.hasChunkFromJava(this.context, pos.x(), pos.z());
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Submits to the write executor, dropping the task if the context is already being torn down.
     */
    private void executeWrite(Runnable task) {
        try {
            this.writeExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // destroy() has shut the executor down; the cache no longer matters
        }
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks) {
        this.executeWrite(() -> {
            writeLock.lock();
            try {
                this.boi.chunkPtr = 0L;
                NetherPathfinder.cullFarChunks(this.context, chunkX, chunkZ, maxDistanceBlocks);
            } finally {
                writeLock.unlock();
            }
        });
    }

    public void queueForPacking(final LevelChunk chunkIn) {
        final SoftReference<LevelChunk> ref = new SoftReference<>(chunkIn);
        this.executeWrite(() -> {
            // TODO: Prioritize packing recent chunks and/or ones that the path goes through,
            //       and prune the oldest chunks per chunkPackerQueueMaxSize
            final LevelChunk chunk = ref.get();
            if (chunk != null) {
                writeLock.lock();
                try {
                    // we might free this chunk
                    this.boi.chunkPtr = 0L;
                    long ptr = NetherPathfinder.allocateAndInsertChunk(this.context, chunk.getPos().x(), chunk.getPos().z());
                    writeChunkData(chunk, ptr);
                } finally {
                    writeLock.unlock();
                }
            }
        });
    }

    public void queueBlockUpdate(BlockChangeEvent event) {
        this.executeWrite(() -> {
            ChunkPos chunkPos = event.getChunkPos();
            // not inserting or deleting from the cache hashmap but it would still be bad for this function to race with itself
            writeLock.lock();
            try {
                long ptr = NetherPathfinder.getChunk(this.context, chunkPos.x(), chunkPos.z());
                if (ptr == 0) return; // this shouldn't ever happen
                event.getBlocks().forEach(pair -> {
                    BlockPos pos = pair.first().below(minY);
                    if (pos.getY() < 0 || pos.getY() >= 384) return;
                    boolean isSolid = pair.second() != AIR_BLOCK_STATE;
                    Octree.setBlock(ptr, pos.getX() & 15, pos.getY(), pos.getZ() & 15, isSolid);
                });
            } finally {
                writeLock.unlock();
            }
        });
    }

    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        final BlockPos adjustedSrc = src.below(minY);
        final BlockPos adjustedDst = dst.below(minY);
        boolean generate = Baritone.settings().elytraPredictTerrain.value && this.dimension == Level.NETHER;
        // pathFind is always a writer, regardless of terrain prediction: it lazily parses baritone cache
        // regions into chunkCache (gated only by checkedRegions, see nether-pathfinder parseBaritoneRegion)
        // and generates terrain. It therefore has to exclude the getChunkOrDefault/raytrace lookups the
        // solvers run under the read lock. Running it under the read lock instead lets those inserts (and the
        // rehash they trigger) race a concurrent lookup's bucket walk, corrupting the map -> SIGSEGV in
        // getChunkOrDefault. The prediction flag now only selects useAirIfChunkNotLoaded.
        Lock l = writeLock;
        ExecutorService exec = writeExecutor;
        try {
            return CompletableFuture.supplyAsync(() -> {
                l.lock();
                try {
                    if (this.destroyed) {
                        throw new PathCalculationException("Pathfinder context destroyed");
                    }
                    final PathSegment segment = NetherPathfinder.pathFind(
                            this.context,
                            adjustedSrc.getX(), adjustedSrc.getY(), adjustedSrc.getZ(),
                            adjustedDst.getX(), adjustedDst.getY(), adjustedDst.getZ(),
                            !Baritone.settings().elytraAllowTightSpaces.value, // atleastX4
                            false, // refine
                            // The native cancel is a no-op (nether-pathfinder sets ctx->cancelFlag but the
                            // search loop polls an unrelated global), so this timeout is the only bound on
                            // how long destroy() can be stuck waiting for an in-flight search. Successful
                            // segments return ~500ms after first progress; only hopeless searches run it out.
                            3000, // timeoutMs
                            !generate, // useAirIfChunkNotLoaded
                            // TODO: Determine appropriate cost value
                            8.0 // fakeChunkCost
                    );
                    if (segment == null) {
                        throw new PathCalculationException("Path calculation failed");
                    }

                    return new UnpackedSegment(UnpackedSegment.from(segment).collect().stream().map(pos -> pos.above(minY)), segment.finished);
                } finally {
                    l.unlock();
                }
            }, exec);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new PathCalculationException("Pathfinder context destroyed"));
        }
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param startX The start X coordinate
     * @param startY The start Y coordinate
     * @param startZ The start Z coordinate
     * @param endX   The end X coordinate
     * @param endY   The end Y coordinate
     * @param endZ   The end Z coordinate
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final double startX, final double startY, final double startZ,
                            final double endX, final double endY, final double endZ) {
        final double adjustedStartY = startY - this.minY;
        final double adjustedEndY = endY - this.minY;
        readLock.lock();
        try {
            if (this.destroyed) {
                return false;
            }
            return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, startX, adjustedStartY, startZ, endX, adjustedEndY, endZ);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param start The starting point
     * @param end   The ending point
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        final Vec3 adjustedStart = start.subtract(0, this.minY, 0);
        final Vec3 adjustedEnd = end.subtract(0, this.minY, 0);
        readLock.lock();
        try {
            if (this.destroyed) {
                return false;
            }
            return NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, adjustedStart.x, adjustedStart.y, adjustedStart.z, adjustedEnd.x, adjustedEnd.y, adjustedEnd.z);
        } finally {
            readLock.unlock();
        }
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        readLock.lock();
        try {
            if (this.destroyed) {
                return false;
            }
            switch (visibility) {
                case Visibility.ALL:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, false) == -1;
                case Visibility.NONE:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) == -1;
                case Visibility.ANY:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) != -1;
                default:
                    throw new IllegalArgumentException("lol");
            }
        } finally {
            readLock.unlock();
        }
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        readLock.lock();
        try {
            if (this.destroyed) {
                Arrays.fill(hitsOut, true);
                return;
            }
            NetherPathfinder.raytrace(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, hitsOut, hitPosOut);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Callers already hold the read lock (see {@link ElytraBehavior#solveAngles}) - this is the
     * solver's innermost loop and taking it again per block would cost more than the call itself.
     * {@link #destroyed} is set under the write lock, so it cannot flip while a caller is inside.
     */
    public boolean passable(int x, int y, int z) {
        if (this.destroyed) {
            return false;
        }
        return !this.boi.get0(x, y, z);
    }

    public void cancel() {
        NetherPathfinder.cancel(this.context);
    }

    // Generous relative to the 3000ms native pathFind timeout, which does not bound findAir's BFS or the
    // region-file IO a search can trigger. A normal teardown finishes well inside this; overshooting it
    // means a search is genuinely wedged in native code and this context is abandoned rather than freed.
    private static final long TEARDOWN_TIMEOUT_MS = 6000;

    public void destroy() {
        this.cancel();
        // Ignore anything that was queued up, just shutdown the executor
        this.readExecutor.shutdownNow();
        this.writeExecutor.shutdownNow();

        boolean terminated;
        try {
            // The native cancel is a no-op, so shutdownNow only drops queued tasks; an in-flight pathFind
            // runs to its own timeout. Bound the wait: a wedged native call must not pin this teardown
            // forever, because that never releases npfSema and silently kills every later engage.
            terminated = this.readExecutor.awaitTermination(TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    & this.writeExecutor.awaitTermination(TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminated = false;
        }

        if (!terminated) {
            // A search is still running in native code. freeContext now would be a use-after-free, and the
            // write lock is held by that same wedged search (pathFind is a writer), so taking it would
            // deadlock. Abandon (leak) this context instead: mark it destroyed so nothing new calls in, and
            // let the next engage build a fresh one. The leak is bounded to one context per wedged search.
            this.destroyed = true;
            return;
        }

        // The executors are drained, but the solver and render threads still raytrace through this
        // context; the write lock keeps freeContext from pulling the memory out from under them.
        writeLock.lock();
        try {
            this.destroyed = true;
            this.boi.chunkPtr = 0L;
            NetherPathfinder.freeContext(this.context);
        } finally {
            writeLock.unlock();
        }
    }

    public long getSeed() {
        return this.seed;
    }

    public void acquireReadLock() {
        this.readLock.lock();
    }

    public boolean tryAcquireReadLock() {
        return this.readLock.tryLock();
    }

    public void releaseReadLock() {
        this.readLock.unlock();
    }

    public int getMaxHeight() {
        return this.maxHeight;
    }

    private static void writeChunkData(LevelChunk chunk, long chunkPtr) {
        try {
            LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
            final int maxSections = Math.min(chunkInternalStorageArray.length, 24); // pathfinder support stops at 384/16 sections
            for (int y0 = 0; y0 < maxSections; y0++) {
                final LevelChunkSection extendedblockstorage = chunkInternalStorageArray[y0];
                if (extendedblockstorage == null || extendedblockstorage.hasOnlyAir()) {
                    continue;
                }
                final PalettedContainer<BlockState> bsc = extendedblockstorage.getStates();
                IPalettedContainer<BlockState> iPalettedContainer = (IPalettedContainer<BlockState>) bsc;
                var palette = iPalettedContainer.getPalette();
                // Mushrooms spawn on the roof and writing them as solid will cause pages to be unnecessarily allocated.
                // idFor can't be used because it may update the palette
                int airId = -1;
                int caveAirId = -1;
                int redMushroomId = -1;
                int brownMushroomId = -1;
                for (int i = 0; i < palette.getSize(); i++) {
                    BlockState bs = palette.valueFor(i);
                    if (bs == Blocks.AIR.defaultBlockState()) airId = i;
                    else if (bs == Blocks.CAVE_AIR.defaultBlockState()) caveAirId = i;
                    else if (bs == Blocks.RED_MUSHROOM.defaultBlockState()) redMushroomId = i;
                    else if (bs == Blocks.BROWN_MUSHROOM.defaultBlockState()) brownMushroomId = i;
                }
                if (airId == -1 & caveAirId == -1) {
                    final long bytesInSection = SECTION_SIZE / 8;
                    UNSAFE.setMemory(chunkPtr + (y0 * bytesInSection), bytesInSection, (byte) 0xFF);
                    continue;
                }
                // pasted from FasterWorldScanner
                final BitStorage array = iPalettedContainer.getStorage();
                if (array == null) continue;
                final long[] longArray = array.getRaw();
                final int arraySize = array.getSize();
                int bitsPerEntry = array.getBits();
                long maxEntryValue = (1L << bitsPerEntry) - 1L;

                final int yReal = y0 << 4;
                for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
                    long l = longArray[i];
                    for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, ++idx) {
                        int value = (int) ((l >> offset) & maxEntryValue);
                        int x = (idx & 15);
                        int y = yReal + (idx >> 8);
                        int z = ((idx >> 4) & 15);

                        // Avoid unnecessary writes that may trigger a page allocation
                        if (!(value == airId | value == caveAirId) & value != redMushroomId & value != brownMushroomId) {
                            Octree.setBlock(chunkPtr, x, y, z, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }

    public static final class Visibility {
        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;
        private Visibility() {}
    }
}
