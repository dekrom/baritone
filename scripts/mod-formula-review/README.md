# Cross-mod arithmetic regressions

Run from the Baritone repository, with XaeroPlus, Sodium, Voxy and the Xaero 26.2 reference sources in sibling directories. These scripts require the local Java 25 installation. The rendering harness uses the FastUtil 8.5.18 and JSpecify 1.0.0 jars already in the Gradle cache.

```sh
python3 scripts/mod-formula-review/verify_coordinates.py ../xaeroplus/common/src/main/java/xaeroplus/util/ChunkUtils.java
python3 scripts/mod-formula-review/verify_maps.py
python3 scripts/mod-formula-review/verify_rendering.py
```

The coordinate, map and priority harnesses extract methods from the working source. The estimator and allocator harnesses compile the production classes directly. Independent grid-containment, weighted least-squares and boolean-array checks supply the expected results. Temporary compilation files are removed automatically. These checks do not initialize Minecraft or prove runtime mixin compatibility.

Baritone's six goal regressions run with `./gradlew test` under Java 25. Persistent Xaero client tests are in its `fabric/src/gametest` source set: `XaeroPlusCoordinateGameTest` and `XaeroPlusRegionDistanceGameTest`.

To reproduce the unresolved highlight-cap issue, run:

```sh
python3 scripts/mod-formula-review/reproduce_highlight_cap.py
```

That script creates an in-memory SQLite database with 3,000,001 synthetic rows. It never opens or changes real map databases. Its expected output is `CONFIRMED`, demonstrating omitted highlights after zooming into a truncated window.
