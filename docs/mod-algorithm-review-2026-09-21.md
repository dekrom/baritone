# Stash-hunting mod algorithm review — 2026-09-21

This is a focused source audit of the local Minecraft 26.2 forks: Baritone, XaeroWorldMap, XaeroPlus, XaeroMinimap, Voxy and Sodium. Priority was long-distance navigation, map coordinates/history, detection logic and rendering during travel. It is not an exhaustive audit of every algorithm. Existing local changes were preserved.

## Applied fixes

| Component | Confirmed failure | Applied correction |
| --- | --- | --- |
| Baritone `FindCommand` | `#find` passed the player's Y as the cache search's Z center. At Z=1,000,000 and Y=64, it searched around Z=64. | Pass `origin.z`; cached-block searches now center on the player's horizontal position. |
| Baritone `GoalNear` | Squared `int` distances overflow: a point 65,536 blocks away can be accepted by a two-block goal. The radius square can overflow too. | Widen coordinates before subtraction/multiplication and store the squared radius as `long`. |
| Baritone `GoalRunAway` | Distance squares overflow, and casting the requested squared radius to `int` clips large distances and truncates fractional ones. Hashing a goal without a Y constraint also throws. | Preserve the squared radius as `double`, widen coordinate arithmetic and hash nullable Y safely. |
| XaeroPlus chunk/region conversions | Casting a negative fractional coordinate before shifting selects the wrong cell: -0.5 becomes chunk 0 instead of -1. This also affects dimension-projected map centers. | Floor before shifting by 4 or 9. |
| XaeroPlus map cursor and Baritone integration | Overworld→Nether conversion truncates toward zero: -1/8 becomes block 0 instead of -1. | Floor the scaled coordinates consistently for cursor text and both Baritone goal constructors. |
| XaeroWorldMap region sorting, via XaeroPlus | Three distance formulas square integers. A 50,000-region delta or 46,400-chunk delta can overflow into a computed distance of zero, prioritizing distant data incorrectly. | Existing `MixinLeveledRegion` now widens shifts/subtractions and squares doubles in all three methods. |
| XaeroPlus highlight deletion | When no tile selection exists, right-click block coordinates are treated as chunk coordinates. | Convert the fallback coordinates to chunks. Normal selection behavior is unchanged; this is a less common fallback-path fix. |
| Sodium task priority | The close-distance penalty uses `max(distance, 50)` although it should saturate at 50. It penalizes all nearby chunks equally and grows without the intended cap. | Use `min`; close-distance contribution is now 0, 0.3, 0.6, 0.6 at 0, 25, 50 and 512 blocks. Other priority weights are preserved. |
| Sodium duration estimation | A large first batch can leave the shared estimator permanently untrained; constant-size samples can produce zero duration. Old covariance/variance are merged around the wrong mean. | Correct warmup/sample accounting, use the observed mean when variance is zero, and recenter historical moments before merging. Bulk job completion can trigger the large-first-batch case; uploads also benefit from the constant-input and recentering fixes. |
| Voxy contiguous node allocation | With capacity 16, freeing alternating slots and requesting two contiguous slots returns [16,18), outside capacity. Exact fits are rejected, and the mask for 64 slots is zero. | Bound hierarchy searches and candidate ranges, permit exact fits, handle a 64-bit mask, and reject invalid counts. |

WorldMap is supplied as reference/decompiled source here, so its fix lives in the buildable XaeroPlus integration. No separate Minimap arithmetic defect met the same evidence threshold in the waypoint/distance paths reviewed; no direct Minimap patch was made.

## Validation

- Baritone: full offline Java 25 build passed, including 58 JUnit tests; six new goal tests cover overflow, fractional/large radii, multiple origins, height constraints and hashing. Four of those tests failed on the original source before the fixes.
- XaeroPlus: full offline build passed for Fabric and NeoForge; both new Fabric client regression classes compiled. Coordinate harness passed 147,560 assertions. Region-distance harness passed 31,158 comparisons against an independent `hypot`/scaled-coordinate calculation; dimension conversion passed 70 checks.
- Sodium and Voxy: full offline builds passed. Direct production-class regressions cover estimator warmup, constant inputs, deferred batches and shifted means against independently computed weighted least squares. Allocator tests include 80,000 deterministic randomized operations against a boolean-array oracle, exact fits, 64-slot allocations and exhaustion at the 24-bit boundary. Priority method checks cover distance, visibility and task age.
- Runtime Xaero client validation: the default runner could not launch because `xvfb-run` is absent. A temporary Gradle override used the available desktop display. Both new tests completed before the runner entered the existing `XaeroPlusRegionIndexGameTest`; that test timed out starting its temporary dedicated server, whose log says its EULA was not accepted. Thus the new coordinate and applied WorldMap mixin checks ran in Minecraft, while the complete integration suite did not pass. No EULA or repository test-runner setting was changed. Log: `/tmp/xaeroplus-formula-display-test.log`.
- These checks establish arithmetic/build correctness, not improved FPS or detection accuracy on 2b2t. No production profile or stash database was modified.

Reproducible numerical checks are retained in [scripts/mod-formula-review](../scripts/mod-formula-review/README.md). Baritone tests are in [GoalDistanceTest.java](../src/test/java/baritone/pathing/goals/GoalDistanceTest.java); Xaero tests are in its sibling `fabric/src/gametest` source set.

## Remaining findings, in priority order

1. **XaeroPlus highlight cap can hide history after zoom-in.** `ChunkHighlightDatabase` caps queries at 3,000,000 rows, but `ChunkHighlightCacheDimensionHandler` subsequently excludes the entire previous rectangle as if it had loaded completely. A synthetic database of 3,000,001 entries reproduces a missing highlight that remains absent after zooming inward. A fully populated radius-28 window can exceed the limit; the default radius 24 fits below it. Carry complete/truncated status with results and reload incomplete views without excluding them. Keep dirty entries separately when replacing persisted snapshots, and enforce a total resident budget. Removing the SQL cap alone restores the memory problem. The retained SQLite reproduction uses only an in-memory synthetic database.
2. **XaeroPlus portal scans can outlive reset/cancellation.** `PortalSkipDetection` cancels futures without checking interruption inside its loops, shares mutable scan buffers, and queues results without a world/dimension generation check. Old work can overwrite a newer scan after switching worlds/dimensions or disabling the module. Use per-task buffers, cancellation checks and a generation token checked before publishing on the client thread. This needs controlled concurrency tests before changing cache lifecycle behavior.
3. **WorldMap right-click distance mixes dimensions.** The inspected `GuiMap`→`DistanceUtils` path compares displayed-map coordinates with raw player coordinates. An Overworld player at X=8000 looking at Nether X=1000 can see a misleading 7000m. Convert the target into the player's coordinate scale before measuring. This is separate from the fixed cursor and Baritone target rounding.
4. **WorldMap skinny PNG exports can produce a zero dimension.** `PNGExporter` truncates scaled output dimensions and then divides by output height. The arithmetic reproduces a zero height for very wide, one-chunk-high selections. Clamp both dimensions to at least one and validate pixel budgets with widened arithmetic before allocating.
5. **Baritone ETA calculation still enumerates large goal volumes.** The no-argument `GoalNear.heuristic()` and `GoalRunAway.heuristic()` have cubic scans. Large-radius membership is now correct, but that does not make large-radius ETA calculation cheap. Replace enumeration with a validated analytical bound or separately defined ETA estimate. Current widened membership arithmetic is validated for normal Minecraft world coordinates, not every arbitrary integer triple.

## Detection improvements requiring data

`PaletteNewChunks` misses unused trailing entries in a linear palette, although it detects mismatched encounter order. Recognizing those entries is a plausible consistency improvement, but both generation and later edits can leave unused palette entries. Validate against labeled saved chunk packets before changing the classification rule or its three-section threshold.

`OldChunks` and `LiquidNewChunks` infer history from terrain/block content and update patterns; neither establishes generation age with certainty. In particular, the liquid detector accepts an adjacent source without matching its fluid type to the updated flow. A same-fluid check is worth testing against captured updates. No arbitrary scoring weights or detection thresholds were changed.

Portal scanning is a separate performance opportunity: an eligibility grid plus summed-area or sliding-window counts can identify the same all-eligible squares without repeatedly checking every member. Test equivalence on randomized masks, dimension boundaries and radius 0/1 before replacing the current algorithm.

## Built artifacts

- Baritone: `fabric/build/libs/baritone-api-fabric-26.2-SNAPSHOT.jar`
- XaeroPlus: `../xaeroplus/fabric/build/libs/XaeroPlus-2.35.3+fabric-26.2-WM1.45.0-MM26.4.2.jar`
- Sodium: `../sodium/build/mods/sodium-fabric-0.9.2-SNAPSHOT+mc26.2-local.jar`
- Voxy: `../voxy/build/libs/voxy-0.2.19-beta-337b919.jar`

These are build outputs. Installation into the active game profile was outside this review.
