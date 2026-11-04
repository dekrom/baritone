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

package baritone.cache;

import baritone.Baritone;
import baritone.api.cache.ICachedWorld;
import baritone.api.utils.Helper;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The RAM tier of the chunk cache. The disk tier (one .bcr per region) keeps everything ever seen; RAM holds
 * only regions in use: written by the packer, read by pathing or scanning. One shared daemon thread writes
 * changed regions every {@code cachedRegionsSaveIntervalSeconds} and evicts idle or over-budget regions
 * ({@link RegionRetentionPolicy}); a region is never evicted while it has unsaved changes. Pathing misses
 * pull regions back from disk in the background.
 *
 * @author Brady
 * @since 8/4/2018
 */
public final class CachedWorld implements ICachedWorld, Helper {

    /**
     * The maximum number of regions in any direction from (0,0)
     */
    private static final int REGION_MAX = 30_000_000 / 512 + 1;

    private static final ChunkPos PACKER_STOP = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);

    private static final long MAINTENANCE_TICK_SECONDS = 5;

    private static final int KNOWN_ABSENT_MAX = 65_536;

    private static final ScheduledExecutorService MAINTENANCE = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("baritone-cache-maintenance").setDaemon(true).build());

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("baritone-cache-loader").setDaemon(true).build());

    private static final Set<CachedWorld> ACTIVE = ConcurrentHashMap.newKeySet();

    static {
        MAINTENANCE.scheduleWithFixedDelay(CachedWorld::maintainAll, MAINTENANCE_TICK_SECONDS, MAINTENANCE_TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * A map of all of the cached regions.
     */
    private final Long2ObjectMap<CachedRegion> cachedRegions = new Long2ObjectOpenHashMap<>();

    /**
     * The directory that the cached region files are saved to
     */
    private final String directory;

    /**
     * False when the directory could not be created; every disk access is skipped instead of throwing per region
     */
    private final boolean diskAvailable;

    /**
     * Queue of positions to pack. Refers to the toPackMap, in that every element of this queue will be a
     * key in that map.
     */
    private final LinkedBlockingQueue<ChunkPos> toPackQueue = new LinkedBlockingQueue<>();

    /**
     * All chunk positions pending packing. This map will be updated in-place if a new update to the chunk occurs
     * while waiting in the queue for the packer thread to get to it.
     */
    private final Map<ChunkPos, LevelChunk> toPackMap = CacheBuilder.newBuilder().softValues().<ChunkPos, LevelChunk>build().asMap();

    /**
     * Region ids with a disk load queued on the loader thread. Guarded by this.
     */
    private final LongSet loadsInFlight = new LongOpenHashSet();

    /**
     * Region ids known to have no file on disk, so pathing misses stop hitting the disk. Guarded by this.
     */
    private final LongSet knownAbsent = new LongOpenHashSet();

    private final DimensionType dimension;

    private volatile boolean active;

    private long lastSaveNanos = System.nanoTime();

    CachedWorld(Path directory, DimensionType dimension) {
        boolean available = true;
        try {
            Files.createDirectories(directory);
        } catch (IOException | InvalidPathException e) {
            available = false;
            System.out.println("Baritone cache directory " + directory + " cannot be created (" + e + "); disk caching is disabled for this world");
        }
        this.diskAvailable = available;
        this.directory = directory.toString();
        this.dimension = dimension;
        System.out.println("Cached world directory: " + directory);
        activate();
    }

    /**
     * Starts the packer for this world and puts it under maintenance. Idempotent.
     */
    final void activate() {
        synchronized (this) {
            if (active) {
                return;
            }
            active = true;
        }
        ACTIVE.add(this);
        Baritone.getExecutor().execute(new PackerThread());
    }

    /**
     * Called when the game leaves this world: the packer drains what is queued, then saves and evicts every region.
     */
    final void close() {
        synchronized (this) {
            if (!active) {
                return;
            }
            active = false;
        }
        ACTIVE.remove(this);
        toPackQueue.add(PACKER_STOP);
    }

    @Override
    public final void queueForPacking(LevelChunk chunk) {
        if (toPackMap.put(chunk.getPos(), chunk) == null) {
            toPackQueue.add(chunk.getPos());
        }
    }

    @Override
    public final boolean isCached(int blockX, int blockZ) {
        CachedRegion region = peekRegion(blockX >> 9, blockZ >> 9);
        if (region == null) {
            return false;
        }
        return region.isCached(blockX & 511, blockZ & 511);
    }

    public final boolean regionLoaded(int blockX, int blockZ) {
        return peekRegion(blockX >> 9, blockZ >> 9) != null;
    }

    @Override
    public final ArrayList<BlockPos> getLocationsOf(String block, int maximum, int centerX, int centerZ, int maxRegionDistanceSq) {
        ArrayList<BlockPos> res = new ArrayList<>();
        int centerRegionX = centerX >> 9;
        int centerRegionZ = centerZ >> 9;

        int searchRadius = 0;
        while (searchRadius <= maxRegionDistanceSq) {
            for (int xoff = -searchRadius; xoff <= searchRadius; xoff++) {
                for (int zoff = -searchRadius; zoff <= searchRadius; zoff++) {
                    int distance = xoff * xoff + zoff * zoff;
                    if (distance != searchRadius) {
                        continue;
                    }
                    int regionX = xoff + centerRegionX;
                    int regionZ = zoff + centerRegionZ;
                    CachedRegion region = getOrCreateRegion(regionX, regionZ);
                    if (region != null) {
                        // TODO: 100% verify if this or addAll is faster.
                        res.addAll(region.getLocationsOf(block));
                    }
                }
            }
            if (res.size() >= maximum) {
                return res;
            }
            searchRadius++;
        }
        return res;
    }

    private void updateCachedChunk(CachedChunk chunk) {
        while (true) {
            CachedRegion region = getOrCreateRegion(chunk.x >> 5, chunk.z >> 5);
            if (region.updateCachedChunk(chunk.x & 31, chunk.z & 31, chunk)) {
                return;
            }
            // evicted between the lookup and the write; both sides hold the region lock, so retry on the live instance
        }
    }

    @Override
    public final void save() {
        if (!Baritone.settings().chunkCaching.value) {
            System.out.println("Not saving to disk; chunk caching is disabled.");
            return;
        }
        int saved = saveDirtyRegions();
        if (saved == 0) {
            System.out.println("World save: nothing changed");
        }
    }

    /**
     * @return how many regions had changes and were written
     */
    private int saveDirtyRegions() {
        if (!diskAvailable) {
            return 0;
        }
        List<CachedRegion> dirty = new ArrayList<>();
        for (CachedRegion region : allRegions()) {
            if (region != null && region.hasUnsavedChanges()) {
                dirty.add(region);
            }
        }
        if (dirty.isEmpty()) {
            return 0;
        }
        long start = System.nanoTime() / 1000000L;
        dirty.parallelStream().forEach(region -> region.save(this.directory));
        long now = System.nanoTime() / 1000000L;
        System.out.println("World save of " + dirty.size() + " regions took " + (now - start) + "ms");
        return dirty.size();
    }

    private static void maintainAll() {
        for (CachedWorld world : ACTIVE) {
            try {
                world.maintain();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    private void maintain() {
        long now = System.nanoTime();
        if (now - lastSaveNanos >= Baritone.settings().cachedRegionsSaveIntervalSeconds.value * 1_000_000_000L) {
            lastSaveNanos = now;
            if (Baritone.settings().chunkCaching.value) {
                saveDirtyRegions();
            } else {
                allRegions().forEach(region -> {
                    if (region != null) {
                        region.removeExpired();
                    }
                }); // even if we aren't saving to disk, still delete expired old chunks from RAM
            }
        }
        evict(false);
    }

    /**
     * Runs on the packer thread after it drained the queue for a closed world.
     */
    private void saveAndEvictAll() {
        if (Baritone.settings().chunkCaching.value) {
            saveDirtyRegions();
        }
        if (active) {
            // re-entered this world while the old packer was still draining; the live packer's regions stay
            return;
        }
        evict(true);
    }

    /**
     * Drops regions per {@link RegionRetentionPolicy}. A region that turned dirty since the policy looked at it
     * refuses the eviction under its own lock and stays until the next save.
     */
    private void evict(boolean everything) {
        if (!everything && !Baritone.settings().pruneRegionsFromRAM.value) {
            return;
        }
        List<CachedRegion> regions = allRegions();
        List<RegionRetentionPolicy.Candidate> candidates = new ArrayList<>(regions.size());
        for (CachedRegion region : regions) {
            if (region != null) {
                candidates.add(new RegionRetentionPolicy.Candidate(
                        getRegionID(region.getX(), region.getZ()),
                        region.getLastAccessMs(),
                        region.hasUnsavedChanges(),
                        region.approximateHeapBytes()));
            }
        }
        LongList victims = RegionRetentionPolicy.select(
                candidates,
                Baritone.settings().cachedRegionsRamBudgetMB.value * 1024L * 1024L,
                Baritone.settings().cachedRegionsIdleSeconds.value * 1000L,
                System.currentTimeMillis(),
                everything);
        if (victims.isEmpty()) {
            return;
        }
        int evicted = 0;
        synchronized (this) {
            for (int i = 0; i < victims.size(); i++) {
                long id = victims.getLong(i);
                CachedRegion region = cachedRegions.get(id);
                if (region != null && region.tryEvict()) {
                    cachedRegions.remove(id);
                    evicted++;
                }
            }
        }
        if (evicted > 0) {
            logDebug("Evicted " + evicted + " cached regions from RAM");
        }
    }

    private synchronized List<CachedRegion> allRegions() {
        return new ArrayList<>(this.cachedRegions.values());
    }

    @Override
    public final void reloadAllFromDisk() {
        long start = System.nanoTime() / 1000000L;
        allRegions().forEach(region -> {
            if (region != null) {
                region.load(this.directory);
            }
        });
        long now = System.nanoTime() / 1000000L;
        System.out.println("World load took " + (now - start) + "ms");
    }

    private synchronized CachedRegion peekRegion(int regionX, int regionZ) {
        return cachedRegions.get(getRegionID(regionX, regionZ));
    }

    /**
     * The region if it is in RAM. A miss queues a background load from disk, so a path calculation that reaches
     * terrain explored in an earlier session sees it on its next attempt.
     */
    @Override
    public final CachedRegion getRegion(int regionX, int regionZ) {
        long id = getRegionID(regionX, regionZ);
        synchronized (this) {
            CachedRegion region = cachedRegions.get(id);
            if (region != null) {
                region.touch();
                return region;
            }
            if (!diskAvailable
                    || !Baritone.settings().cachedRegionsLoadOnDemand.value
                    || knownAbsent.contains(id)
                    || !loadsInFlight.add(id)) {
                return null;
            }
        }
        LOADER.execute(() -> loadFromDisk(regionX, regionZ));
        return null;
    }

    private void loadFromDisk(int regionX, int regionZ) {
        long id = getRegionID(regionX, regionZ);
        try {
            if (!Files.exists(CachedRegion.getRegionFile(Paths.get(directory), regionX, regionZ))) {
                synchronized (this) {
                    if (knownAbsent.size() >= KNOWN_ABSENT_MAX) {
                        knownAbsent.clear();
                    }
                    knownAbsent.add(id);
                }
                return;
            }
            CachedRegion region = new CachedRegion(regionX, regionZ, dimension);
            region.load(this.directory);
            synchronized (this) {
                cachedRegions.putIfAbsent(id, region);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            synchronized (this) {
                loadsInFlight.remove(id);
            }
        }
    }

    /**
     * Returns the region at the specified region coordinates. If a
     * region is not found, then a new one is created (loaded from disk when a file exists).
     * The disk read happens outside the world lock so pathing lookups are not stalled by it.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return The region located at the specified coordinates
     */
    private CachedRegion getOrCreateRegion(int regionX, int regionZ) {
        long id = getRegionID(regionX, regionZ);
        synchronized (this) {
            CachedRegion region = cachedRegions.get(id);
            if (region != null) {
                region.touch();
                return region;
            }
        }
        CachedRegion fresh = new CachedRegion(regionX, regionZ, dimension);
        if (diskAvailable) {
            fresh.load(this.directory);
        }
        synchronized (this) {
            CachedRegion existing = cachedRegions.putIfAbsent(id, fresh);
            knownAbsent.remove(id);
            CachedRegion region = existing != null ? existing : fresh;
            region.touch();
            return region;
        }
    }

    public void tryLoadFromDisk(int regionX, int regionZ) {
        getOrCreateRegion(regionX, regionZ);
    }

    /**
     * Returns the region ID based on the region coordinates. 0 will be
     * returned if the specified region coordinates are out of bounds.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return The region ID
     */
    private long getRegionID(int regionX, int regionZ) {
        if (!isRegionInWorld(regionX, regionZ)) {
            return 0;
        }

        return (long) regionX & 0xFFFFFFFFL | ((long) regionZ & 0xFFFFFFFFL) << 32;
    }

    /**
     * Returns whether or not the specified region coordinates is within the world bounds.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return Whether or not the region is in world bounds
     */
    private boolean isRegionInWorld(int regionX, int regionZ) {
        return regionX <= REGION_MAX && regionX >= -REGION_MAX && regionZ <= REGION_MAX && regionZ >= -REGION_MAX;
    }

    private class PackerThread implements Runnable {

        public void run() {
            while (true) {
                try {
                    ChunkPos pos = toPackQueue.take();
                    if (pos == PACKER_STOP) {
                        saveAndEvictAll();
                        return;
                    }
                    LevelChunk chunk = toPackMap.remove(pos);
                    if (chunk == null) {
                        continue; // soft reference collected before we got to it
                    }
                    if (toPackQueue.size() > Baritone.settings().chunkPackerQueueMaxSize.value) {
                        continue;
                    }
                    CachedChunk cached = ChunkPacker.pack(chunk);
                    CachedWorld.this.updateCachedChunk(cached);
                    //System.out.println("Processed chunk at " + chunk.x + "," + chunk.z);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                } catch (Throwable th) {
                    // in the case of an exception, keep consuming from the queue so as not to leak memory
                    th.printStackTrace();
                }
            }
        }
    }
}
