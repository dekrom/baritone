# Baritone

<p align="center">
  <a href="https://github.com/dekrom/baritone/actions/workflows/gradle_build.yml"><img src="https://github.com/dekrom/baritone/actions/workflows/gradle_build.yml/badge.svg" alt="Build"/></a>
  <a href="https://github.com/dekrom/baritone/actions/workflows/run_tests.yml"><img src="https://github.com/dekrom/baritone/actions/workflows/run_tests.yml/badge.svg" alt="Tests"/></a>
  <a href="https://github.com/dekrom/baritone/releases/"><img src="https://img.shields.io/github/v/release/dekrom/baritone?sort=date" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-LGPL--3.0-green.svg" alt="License"/></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/MC-1.21.4-brightgreen.svg" alt="1.21.4"/>
  <img src="https://img.shields.io/badge/MC-1.21.5-brightgreen.svg" alt="1.21.5"/>
  <img src="https://img.shields.io/badge/MC-1.21.8-brightgreen.svg" alt="1.21.8"/>
  <img src="https://img.shields.io/badge/MC-1.21.11-brightgreen.svg" alt="1.21.11"/>
  <img src="https://img.shields.io/badge/MC-26.1-brightgreen.svg" alt="26.1"/>
  <img src="https://img.shields.io/badge/MC-26.2-brightgreen.svg" alt="26.2"/>
  <img src="https://img.shields.io/badge/MC-26.3-brightgreen.svg" alt="26.3"/>
</p>

A Minecraft pathfinder bot. This is a fork of [Baritone](https://github.com/cabaletta/baritone) by
[leijurv](https://github.com/leijurv), tracking the Fabric branches of
[MeteorDevelopment/baritone](https://github.com/MeteorDevelopment/baritone), with fixes aimed at the
one thing anarchy servers ask of a pathfinder: travelling a very long way, unattended, without
dying. Everything upstream Baritone does — `#goto`, `#mine`, `#follow`, `#build`, `#farm` — works
here unchanged.

Every fix lands on every supported Minecraft version, except the handful of bugs that only ever
existed on one of them.

## Supported versions

| Minecraft | Branch                                              | Java | Release tag       |
|-----------|-----------------------------------------------------|------|-------------------|
| 1.21.4    | [`1.21.4`](https://github.com/dekrom/baritone/tree/1.21.4)   | 21   | `v<version>-1.21.4`  |
| 1.21.5    | [`1.21.5`](https://github.com/dekrom/baritone/tree/1.21.5)   | 21   | `v<version>-1.21.5`  |
| 1.21.8    | [`1.21.8`](https://github.com/dekrom/baritone/tree/1.21.8)   | 21   | `v<version>-1.21.8`  |
| 1.21.11   | [`1.21.11`](https://github.com/dekrom/baritone/tree/1.21.11) | 21   | `v<version>-1.21.11` |
| 26.1      | [`26.1`](https://github.com/dekrom/baritone/tree/26.1)       | 25   | `v<version>-26.1`    |
| 26.2      | [`26.2`](https://github.com/dekrom/baritone/tree/26.2)       | 25   | `v<version>-26.2`    |
| 26.3      | [`26.3`](https://github.com/dekrom/baritone/tree/26.3)       | 25   | `v<version>-26.3`    |

Each Minecraft version has its own branch and its own release. Fabric only.

## Install

1. Grab the jar for your Minecraft version from [Releases](https://github.com/dekrom/baritone/releases).
   - `baritone-api-fabric-*.jar` — use this one if another mod (Meteor Client, an addon, your own
     mod) drives Baritone through its API. This is what most people want.
   - `baritone-standalone-fabric-*.jar` — everything exposed, for using Baritone on its own.
   - `baritone-unoptimized-fabric-*.jar` — not obfuscated. Use it when reporting a bug so the stack
     trace is readable.
2. Drop it in `mods/` next to Fabric API.
3. Type `#goto 1000 500` in chat.

Verify your download against `checksums.txt` on the release. See [SETUP.md](SETUP.md) for anything
more involved, and [USAGE.md](USAGE.md) for the chat commands.

## What this fork changes

### Elytra

`#elytra` is the reason this fork exists. Upstream's implementation is good at flying a computed
path and bad at everything around it: getting into the air, and getting out of trouble once the
world stops matching the path.

- **Takeoff from anywhere.** `elytraAutoJump` only knew how to walk to an overhang and step off it.
  With no overhang in reach — most of the overworld, and most of a nether highway — it spent a
  couple of seconds pathfinding and then told you to go find a cliff. It now falls back to jumping
  on the spot, opening the elytra on the way up the way a vanilla double tap does, and lighting a
  firework the instant it opens. Three ways it could previously stand still forever are fixed as
  well, including landing at exactly y=31 in the nether, where the old fixed takeoff goal was
  already satisfied where you stood.
- **A survival fallback.** When the solver ran out of options it returned a heading with no pitch,
  which in practice means holding the last pitch straight into the terrain. That is exactly the
  case that matters: the world changed under the path, a wall appeared, something knocked you off
  course. It now simulates the pitch range and flies whatever trajectory stays clear the longest,
  preferring the one that ends higher, and forces a firework when nothing else buys enough time.
- **Physics that match the server.** The flight simulation had drifted from
  `LivingEntity.updateFallFlyingMovement`: hardcoded gravity instead of the `GRAVITY` attribute, no
  Slow Falling, a cosine term from `Mth`'s lookup table with a factor vanilla does not have, a
  missing divide-by-zero guard that produced `NaN` when looking straight up or down, and drag as
  `float` where vanilla uses `double`. Every tick of error compounds across the horizon the solver
  picks a pitch from.
- **Recalculating instead of hoping.** A path node buried in terrain used to be noticed and
  ignored. The segment is now recomputed, rejoining the existing path at the last node still in
  open air.
- **`elytraPathLookahead`** exposes how far ahead the solver looks for a node to aim at (was a
  hardcoded 20). Higher cuts corners harder at firework speed.
- **`elytraStandingTakeoff`** turns the standing takeoff on and off. On by default, capped at three
  attempts so a bad spot cannot burn a stack of rockets.
- **Taking off from a hole.** Where a nether flight ends when the rockets run out is where the next
  one starts, and in the basalt deltas that is a crevice with walls a couple of blocks away on every
  side and open sky straight up. The standing takeoff asked for a path from your feet, and the
  pathfinder starts every path from the nearest 4×4×4 cube of air, found by a search that walks
  straight through walls — from a hole, very often a cube on the far side of one. The rocket flew at
  it, hit the wall and dropped back in. The path now starts from the first clear cube straight
  overhead. With a rocket burning, the survival solver takes the pitch that ends highest rather than
  the first one that survives, and it forces a rocket as soon as an impact is anywhere in its
  horizon instead of a dozen ticks out.
- **The takeoff rocket was never accepted.** It was lit in the same tick the elytra opened, which no
  vanilla client can do — its use packet goes out before the glide packet — and it carried the
  camera's rotation while that tick's movement packet carried the takeoff aim, two looks the
  server's movement checks compare. The rocket goes the tick after now, every use packet carries
  the rotation the movement packet will, and the solver assumes a rocket it has just lit is burning
  instead of planning an unboosted glide into the nearest wall while the entity is in transit. A
  takeoff the server refuses is reported as such.
- **Lava.** An elytra opens in lava but does nothing there — vanilla moves you by the fluid rules
  — so the rocket is the only thrust, and it was aimed at a path that leads through the pond's wall
  while the landing logic asked for a new path from inside the pool twenty times a second. In lava
  the process now swims up, opens the elytra, aims straight up and lights rockets until it is out;
  the landing search waits.
- **Unfinished segments.** A recomputation around an obstacle that ran out of time short of its
  rejoin node had the old path stitched on anyway, leaving a blind jump through whatever lay
  between; every recomputation ended the same way, and the solver circled next to a path drawn
  through solid ground. An unfinished segment now stands on its own and is continued from where the
  search got to.
- **A cache that disagrees with the world.** Everything the solver does runs against the
  pathfinder's copy of the world, and when that copy was wrong nothing noticed, because the
  raytraces look at the same copy. A chunk packet the client discarded was packed as a real chunk
  made of air, and a chunk whose packing was missed stayed missed until a cancel. Discarded chunks
  are skipped now, and a collision, a "we are inside a block" verdict or a failed recomputation
  re-reads the chunks around the player.
- **A hung teardown.** The pathfinder context's teardown waited without limit for a native search
  that the cancel cannot interrupt. On 26.2, where the teardown hands back the semaphore the next
  engage needs, one hung search silently killed every later `#elytra` for the session; elsewhere it
  pinned a thread and leaked the context. The wait is bounded now, and a context whose search is
  still wedged after it is abandoned rather than freed underneath it.
- **Cancelling could crash the client.** The nether pathfinder's native context was freed once its
  own executors had drained, but the solver and the game thread raytrace through it outside those
  executors, and the octree interface caches a raw pointer into it. Running one of those against
  freed memory is a segfault, not an exception. It is freed under the lock they hold now, and every
  native call is fenced behind a flag that fails safe.
- **The XaeroPlus path overlay.** XaeroPlus finds the elytra path by field name, and the obfuscated
  jars renamed those fields, so the path never showed on its map. They keep their names now.
- **The collision sweep collapsed when flying backwards.** The box swept ahead of the player to
  check the next tick's motion was built by inflating the hitbox by the motion vector, and inflating
  by a negative amount shrinks a box instead of extending it, so any flight along the negative side
  of an axis — west, north or downwards — checked a box smaller than the player, or no box at all,
  and flew into terrain the raytraces never saw. The box is now extended towards the motion, which
  covers the full swept volume in every direction.
- **A solver wait without a limit.** A cancelled or restarted engage could wait forever on a solver
  run that never got scheduled, hanging `#elytra` until a restart; the wait is bounded to five ticks
  and the solution is recomputed on the spot when it runs out.
- **A path failure reported from the wrong thread.** When the pathfinder could not find a path it
  reported back from its own worker thread straight into the state the game thread owns. It is
  handed to the game thread first now.
- **An out-of-range goal while flying.** Setting a goal with a y outside the nether's 0–128, or one
  that is neither a block nor an XZ goal, while `#elytra` was flying threw an exception out of the
  command instead of a message. It is refused in chat and the flight continues.

### Chunk cache

- Region files were rewritten in place, from the cache save thread, while everything else was
  reading them. A read that landed mid-rewrite got a truncated gzip stream and threw away a
  512×512 region's worth of cached terrain. Saves are written to a temp file and renamed over the
  old one, so a reader sees either the previous region or the new one. On 26.2 and 26.3 this is
  what the nether pathfinder reads with `elytraUseCache` on, and a region it fails to parse is gone
  for the rest of the flight. Waypoint files were rewritten in place the same way, and a crash
  mid-write lost every waypoint of that tag; they get the same treatment.
- **On 1.21.x the elytra pathfinder never reads the cache.** `elytraUseCache` exists only on 26.2
  and 26.3; here the nether pathfinder sees only the chunks the game loaded this session, so the
  settings below shape walking paths and `#find`, and `cachedRegionsSaveIntervalSeconds` decides
  only how much a crash can lose.
- **RAM is bounded.** Regions were only dropped from RAM after the ten minute save, and only by
  distance from the player, so nothing capped how much of the cache stayed resident on a long trip.
  A maintenance thread now saves changed regions every 30 seconds (`cachedRegionsSaveIntervalSeconds`),
  drops regions nothing has touched for three minutes (`cachedRegionsIdleSeconds`), and then the
  least recently used ones while the cache is over 192 MiB (`cachedRegionsRamBudgetMB`). A region
  with unsaved changes stays as long as those changes can still be written; with `chunkCaching` off,
  or no cache directory, nothing ever saves a region, and such regions are evicted like any other
  instead of pinning RAM for the whole session. A path calculation that reaches a region on disk but
  not in RAM loads it back in the background (`cachedRegionsLoadOnDemand`), so terrain seen in an
  earlier session counts again.
- **Overworld water was packed wrong.** The packer handed the block classifier a y relative to the
  section, which it treats as absolute, and the overworld starts at y −64: the checks for flowing
  water next to a block and at a chunk edge read 64 blocks above the real ones, so a water source
  beside flowing water was cached as plain water, and water at y 256 and up indexed past the end of
  the section array and aborted the rest of that chunk's pack. The nether, whose floor is y 0, was
  never affected.
- **Less packing.** The check meant to skip empty sections tested for `null`, which modern chunks
  never are, so every all-air section cost 4096 block lookups to pack to zeros: most of an overworld
  column above the surface and the nether above the roof. Those are skipped now, and a chunk that
  unloads unchanged since it was packed on load is not packed again
  (`repackUnchangedChunksOnUnload` brings that back).
- **Server folder names.** Only Windows had the colon of a `host:port` server replaced, so on an
  NTFS drive mounted under Linux such a server never got a cache directory and every region save
  failed. Names are sanitised on every platform now, and an existing directory under the old name
  keeps being used.

### Pathfinding correctness

- Falls were charged one tick of gravity that never happens, because `velocity(0)` is zero.
- Descending onto a block counted one block of fall too many; the fall ends on top of the block.
- `GoalRunAway` folded its bounding box's max corner against the running minimum, so with more than
  one position the box collapsed towards the minimum corner.
- The dimension height check compared a y coordinate against a block count, letting the search
  climb 64 blocks above the overworld build limit.
- Waterlogged fences, walls, panes, bars, stairs and leaves were treated as plain water and walked
  into.
- `fullyPassable` listed the blocks you cannot jump through one at a time, so powder snow, honey,
  berry bushes, dripstone and anything in `blocksToAvoid` were considered jumpable. The hitbox is
  the same width in the air as on the ground.
- `backtrackCostFavoringCoefficient` at 0 or below produced a non-positive action cost, which A\*
  rejects, failing the whole calculation instead of favoring nothing.
- `GoalGetToBlock` reported an ETA that never counted down to zero.
- `GoalNear` and `GoalRunAway` squared `int` coordinate differences, which overflow: a point 65,536
  blocks away could satisfy a two-block `GoalNear`. `GoalRunAway` also truncated its radius and
  threw when hashed without a y constraint.
- `#find` searched the cache around the player's y where it meant their z.

### Pathing thread

- `ToolSet` read the live inventory from the calculation thread — a data race with the game thread —
  and cached break speeds in a plain `HashMap`. The hotbar and selected slot are now snapshotted on
  construction and the cache is concurrent.
- `assumeWalkOnWater` was read off the settings mid-calculation, so flipping it made the cost
  function inconsistent with the nodes already expanded. It is read once per `BlockStateInterface`.
- `cancelRequested` is written by the game thread and read by the calculation thread, and was not
  `volatile`.
- `bestPathSoFar` allocated a `BetterBlockPos` per node on every tick a calculation was running,
  before the cheap checks that almost always decide the outcome; `PathExecutor` built a whole
  `CalculationContext` per tick to read two fields off it.

### Commands and schematics

- `#path` with no goal set reported that it was pathing. It says `No goal set`.
- `#build` compared the properties of the wanted block state and the placed one by identity rather
  than by value, so a block whose state matched in every way could still be read as wrong, broken
  and placed again.
- The Litematica bridge referenced one of Litematica's world classes in a way that made the JVM
  load it while loading the bridge itself, before Litematica was ready; the bridge failed to load
  and `#litematica` did not see Litematica at all.
- Baritone's own `BetterBlockPos` leaked into Minecraft's block entity map through the reach and
  block-centre helpers, where the game later looked it up with a plain `BlockPos` and missed. The
  helpers hand the game a plain position now.

### 26.2 and 26.3 only

- Nether pathfinder chunk packing wrote solid sections as air, so `#elytra` planned paths straight
  through terrain. The 26.2 port had replaced the removed `LevelChunkSection.SECTION_SIZE` (4096,
  blocks per section) with a local `16`, and the all-solid fast path then memset 2 bytes instead of
  512.
- Baritone's threadpool threads are daemon threads. As of 26.2 the game waits on non-daemon threads
  at shutdown and files a crash report if any are still alive.
- **Cancelling `#elytra` froze the game.** 26.2 keeps one pathfinder context alive across engages,
  and re-engaging blocked the game thread on the old one until it was freed. A path calculation
  cannot be interrupted — nether-pathfinder's `cancel()` sets a flag its search loop does not read —
  so the client hung for whatever the in-flight calculation had left, up to ten seconds. The engage
  is deferred onto the teardown now, and the calculation timeout is three seconds.
- **The endless `Failed to compute path to destination`.** That same persistent context is fed
  Baritone's region cache, and a region it fails to parse is never retried, so one bad read leaves
  a permanent hole in its world and every later calculation fails against it. Three consecutive
  failures discard the context so the next attempt starts from a clean cache; skipped while flying,
  where a reset would drop the elytra.
- The above-build-limit fallback divided by a horizontal distance of zero, making every step of a
  straight-up path `NaN`, and compared a squared distance against an unsquared 32.
- **Segfault in `getChunkOrDefault`.** The native path calculation ran under the read lock whenever
  terrain prediction was off, on the theory that it only reads the cache. With `elytraUseCache` it
  also parses region files into it, and an insert that rehashes the map under the solvers' lookups
  is heap corruption. A path calculation is always a writer now.
- **`#elytra` going idle mid-engage.** An engage deferred behind the old context's teardown left the
  process without a behavior, so it reported itself inactive and dropped its destination; the
  pathing manager stopped ticking it and anything polling for the goal issued it again. It stays
  active and keeps its destination until the engage lands.
- **`#mine` stopped picking up items.** Upstream's #5058 resolved loot tables through the client's
  registries, which never contain them, so every block had no drops. Reverted, as upstream did.

## Building

Java 21 for the `1.21.x` branches, Java 25 for `26.x` (see the table above).

```sh
./gradlew build          # jars land in dist/, with checksums.txt
./gradlew test
```

Tagging `v<version>-<mc>` on a version branch builds it and publishes the release.

## Docs

- [Features](FEATURES.md)
- [Installation & setup](SETUP.md)
- [Usage (chat control)](USAGE.md)
- [Settings](https://baritone.leijurv.com/baritone/api/Settings.html#field.detail)
- [API Javadocs](https://baritone.leijurv.com/) (upstream)

## API

The API is heavily documented. Anything outside the `baritone.api` package is not supported by the
API release jar.

```java
BaritoneAPI.getSettings().allowSprint.value = true;
BaritoneAPI.getSettings().primaryTimeoutMS.value = 2000L;
BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(10000, 20000));
```

## Credits

Baritone is by [leijurv](https://github.com/leijurv) and
[its contributors](https://github.com/cabaletta/baritone/graphs/contributors); it grew out of
[MineBot](https://github.com/leijurv/MineBot/). The Fabric branches this fork tracks are maintained
by [MeteorDevelopment](https://github.com/MeteorDevelopment/baritone). The elytra pathfinder is
[nether-pathfinder](https://github.com/babbaj/nether-pathfinder) by
[babbaj](https://github.com/babbaj).

For help with Baritone itself, the [Baritone Discord](http://discord.gg/s6fRBAUpmr) is the place to
ask. Issues with *this fork* belong [here](https://github.com/dekrom/baritone/issues).

## License

LGPL-3.0, same as upstream. See [LICENSE](LICENSE).
