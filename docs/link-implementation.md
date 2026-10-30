# Link 26.3 implementation and validation

Link is a patch-based ASP `dev/26.3` fork. `link.mode=journal` is an experimental
native implementation, not a production release. `strict` deliberately refuses startup;
`disabled` retains upstream behavior and `tools` provides template tooling with upstream
player files. No zero-loss or zero-duplication guarantee is made.

## Implemented paths

- NMS patches wrap server ticks, server tasks, packet handling and chunk main-thread tasks.
  A frame snapshots tracked players, all loaded slime worlds and Minecraft SavedData into
  one PostgreSQL transaction. Blocks, block inventories and non-player entities are captured
  through the SRF snapshot. Scheduled block/fluid ticks and POI serialization are enabled.
- Player NBT, statistics, advancements, cursor and vanilla transient crafting/trading inputs
  use SQL. Player `.dat`, statistics, advancement and SavedData file writes are bypassed in
  journal mode. Global SavedData restoration starts before Minecraft creates clocks/maps.
  World UUID, level overrides, gamerules, weather, timers and scoreboard state are included.
- Encoded outbound bytes are held until commit, preserving channel order across adjacent
  frames. Failure aborts pending packets and stops the backend. Live Java objects are not
  rolled back in place. An uncertain SQL commit retries the identical operation UUID/payload.
- Immutable versioned slime templates; editor, publish, clone, checkpoint, unload, load and
  tombstone delete. Template exports include world SavedData without copying the editor UUID.
  Native world catalog and unloaded checkpoints are retained. Deleted world names cannot be
  reused, avoiding identity collisions with old saved data.
- Native bootstrap uses the slime spawn template and skips vanilla secondary dimensions.
  A bounded provisioner creates spare spawn clones and only registers them after creation.
  Default world capacity is 50; default backend capacity is 150 and maximum spawn worlds is 3.
  Backends are existing processes; Link does not provision machines or start more JVMs.
- Velocity module: first login reserves a ready spawn, issues a single-use SQL arrival ticket,
  and selects the matching backend. NMS consumes the ticket and sets the reserved first-spawn
  position after spawn-location callbacks. Modern forwarding with an online-mode proxy identity
  and nonempty secret is required by journal backends.
- `/spawn`, `/warp <name>` and plugin travel requests use source checkpoint/freeze, fenced
  ownership transfer, destination admission and proxy routing. Same-backend travel teleports
  locally. The proxy observes READY only after the final native frame commits. SQL occupancy
  counts both reservations and admitted players. Reservation coordinates are immutable.
- DB-clock leases and epochs fence stale writers. Expired unclaimed transfers can reconnect
  with a new epoch; old transfer acceptance is rejected. Recovery may require waiting for
  the 30-second lease. Redis is an optional at-least-once outbox notification relay, never
  the source of truth for inventory or ownership. Redis failure retains the SQL outbox.

## Scope and remaining validation

The implementation journals after-state snapshots, not a separate semantic event for every
mouse click. Ordering is enforced per aggregate/session and atomic frame, not a universal
wall-clock order across independent backends. Plugin HTTP calls, files, custom databases and
other external effects are outside a native frame.

Whole-world serialization and synchronous SQL commits on the main thread favor a clear
experimental boundary over throughput. SQL uses bounded HikariCP pools with connection/socket
timeouts. Large worlds/player counts, incremental snapshots, event retention, outbox trimming and production observability need
further work. This mode must not host valuable player inventories yet.

Real-player click/drag/crafting/trading/hopper/pickup/death/disconnect and multi-backend transfer
crash matrices have not all passed end to end. Arbitrary plugin virtual inventories and
async mutation of Bukkit/NMS objects are not supported. Passenger/vehicle and ender-pearl
association restoration needs additional work: duplicate entity creation from player NBT
is suppressed, but restoring those relationships is not yet validated. Multi-node map ID
allocation/map contents are node-local, not yet a network-global map service.

Global and slime-world SavedData use stable logical node/world namespaces. Earlier path-hashed
entries are migrated during checkpoint; auxiliary plugin-created storage paths can still be
path-dependent. Existing player-file import, backups/PITR,
whole-network rollback policy, idle spawn cleanup and schema-version migration tools remain
unimplemented. Do not restore a single world independently of player state.

## Build and automated checks

Java 25 is required. Integration tests use PostgreSQL 17 and Redis 7. `LINK_TEST_*` must point
to a disposable database: tests truncate Link tables.

```powershell
$env:LINK_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55432/link_test'
$env:LINK_TEST_DB_USER='link_test'
$env:LINK_TEST_DB_PASSWORD='link_test_local'
$env:LINK_TEST_REDIS_URI='redis://127.0.0.1:56379'
.\gradlew.bat :link-core:test
.\gradlew.bat applyAllPatches
.\gradlew.bat :link-velocity:shadowJar :link-integration:jar :aspaper-server:test --tests io.linkmc.server.LinkPacketGateTestSuite
.\gradlew.bat :aspaper-server:createPaperclipJar
```

Do not combine `applyPaperSingleFilePatches` and `applyAllServerPatches` in one invocation:
an upstream implicit-dependency validation issue affects that combination. Windows Git long
paths must be enabled in generated resource repositories too.

Checks exercise multi-aggregate rollback, idempotent commits, uncertain commit retry, stale
owners, admission rollback, single-use arrival tickets, transfer expiry/recovery, concurrent
capacity allocation and route dispatch, immutable warp coordinates, segmented snapshot
integrity, Redis redelivery, packet withholding, abort buffer release and adjacent-frame order.
These tests are not a substitute for real-player and chaos validation.

`link-integration` is a separate disposable-server probe plugin, never bundled into Link.
It requires a `test-*` node ID. Console `linkprobe prepare` stores a chest inventory, equipped
armor stand, world PDC and scoreboard; `linkprobe verify` checks recovery after restart.
`:link-integration:seedTemplate` creates an empty SRF template only in a database named
`link_runtime_test`. Accept the Minecraft EULA explicitly before running a test server.

Runtime checks on 2026-10-30 used two Minecraft 26.3 JVMs, PostgreSQL 17 and Redis 7:

- Forced JVM termination and restart restored a chest with seven diamonds, an equipped
  armor stand, world PDC and the global scoreboard.
- A native `ChestMenu` pickup moved seven diamonds onto a synthetic player's cursor.
  After forced termination, recovery returned exactly seven to the player, left the chest
  empty, and restored health, experience level and ender-chest contents.
- A synthetic native player transferred between two live backends, retained those values
  and arrived at the reserved coordinates. SQL recorded accepted ownership at a new epoch.
- Stopping PostgreSQL during live frames stopped both backend JVMs. Neither continued
  gameplay with uncommitted memory state.
- A TCP protocol client connected through Velocity 4.3.1, entered backend A, changed to
  backend B through configuration/play transitions, and arrived at `(8,66,8)` with yaw 90.
  Seven diamonds, three ender-chest emeralds, level 9 and health 13 were verified on B.
  Same-backend travel also passed. SQL recorded `COMPLETE` and the destination's active epoch.
  Both test worlds used normal difficulty to prevent peaceful-mode healing changing health.
- With that TCP client connected, backend B's JVM was forcibly terminated. After lease
  expiry, reconnecting through Velocity admitted the player on A at its spawn, restored
  the same inventory/ender chest/level/health, and acquired a newer active ownership epoch.

The automated suite passed 29 core tests (23 against real PostgreSQL/Redis) and five Netty
gate tests, with no skips. All eight Minecraft feature patches reapplied successfully.

### Expanded validation (2026-10-30)

Additional fixtures use real connected players but invoke NMS menu methods on the server
thread. They are not a graphical-client input or third-party-plugin compatibility suite.
`linkprobe matrix-menus` checks split/left clicks, shift move, hotbar swap, drag distribution,
crafting output, merchant trade and item pickup. All seven passed. Removed entities are not
called a second time: the first draft of the fixture incorrectly invoked `playerTouch` on
an already discarded Java object and was corrected to match world collision semantics.

| Check | Result | Evidence / boundary |
| --- | --- | --- |
| Crafting input + cursor after forced JVM exit | PASS | Exactly five diamonds and three emeralds recovered |
| Chest + running hopper after forced JVM exit | PASS | Eleven chest diamonds; hopper/destination total sixteen iron ingots |
| Equipped entity after forced JVM exit | PASS | Exactly one tagged armor stand with its helmet |
| Death and respawn | PASS | Seven new nether stars dropped, old inventory cleared, respawn alive |
| Public world API lifecycle | PASS | Editor → publish → clone → save → unload → load → delete retains the template block |
| Ender pearl persistence | PASS | One pearl, correct shooter, player ticket registration after restart |
| Vehicle entity persistence | PASS | Exactly one original boat after restart |
| Player mounted relationship | **FAIL** | Player reconnects dismounted; `RootVehicle` is suppressed by the codec |
| Map pixels across backends | **FAIL** | Map item survives A→B but B has no original saved map pixels |
| Transfer boundary recovery | PASS | Six additional SQL cases: checkpoint, prepared, issued, ready, dispatched, accepted; stale writers fenced |
| 51 protocol clients / capacity | PASS | All 51 joined without errors; one backend distributed 50 + 1 across spawn worlds |
| 51-client performance | **FAIL** | One-minute TPS fell to 4.6; not suitable for the requested operating scale |
| Direct backend connection | PASS | Modern-forwarding backend rejected a connection without Velocity |
| Capacity release on disconnect | PASS | Zero ADMITTED reservations after all 51 clients disconnected |
| Aborted native frame | PASS | After setting inventory to 99 and replacing the chest, injected exception stopped the backend; restart restored inventory 7 and chest 11 |
| Redis unavailable during startup | **FIXED / PASS** | Previously aborted startup; deferred notification connection now allows startup while Redis is down |
| Redis outage after connection | PASS | Backend stayed responsive and renewed DB ownership beyond one lease period; delivery resumed after Redis returned |

The expanded automated suite has 40 passing tests without skips: 29 PostgreSQL/Redis cases,
four frame-commit tests, two segmented-snapshot tests and five Netty gate tests. The six new
transfer boundary cases are database state-machine tests, not six separate live JVM kills.
Redis initialization/retry now runs on `Link-Notifications`, separate from the lease heartbeat
and server bootstrap; durable outbox rows remain pending during failures. Runtime probes
record `MATRIX_PASS` / `MATRIX_FAIL` separately from transaction completion.

Load environment: Windows 11, Java 25, one backend with `-Xmx2G`, local Docker PostgreSQL 17,
Velocity 4.3.1, 51 idle protocol clients launched four seconds apart, then held for 60 seconds.
The backend had its two spawn worlds and an earlier editor world loaded. There was no local
Gradle build during measurement. Observed one-minute TPS: 18.3 at 9 clients, 11.0 at 21,
7.5 at 33, 6.2 at 41, 5.1 at 50 and 4.6 at 51. These are moving averages during a ramp,
not steady-state capacity claims. `/mspt` does not account for all surrounding journal work;
its low values must not be used to override the observed wall-clock TPS loss.

Run `node link-integration/load-clients.cjs 51 4000 60000` only against the disposable loopback
proxy after whitelisting the offline UUIDs of LinkLoad0 through LinkLoad50 and setting the
test backend's max-players above 51. Results are written under ignored `build/load-clients/`.

This matrix is deliberately not “everything passed.” The mounted relationship, cross-node
map service and throughput failures remain release blockers. Online Mojang authentication,
arbitrary plugin virtual menus, async plugin writes, production-size maps and long-running
mixed gameplay/network-partition matrices have not been certified.

The separate `link-integration/wire-client.cjs` derives packet IDs from the patched 26.3
sources and exercises a real TCP connection through Velocity. It requires a disposable
loopback offline-auth proxy on port 25594 and a whitelisted `LinkWire` offline UUID.
This harness does not test Mojang authentication, rendering or all inventory interactions.
The probe commands `platform`, `wire-prepare`, `wire-transfer` and `wire-verify` are console
only; never install this probe on a production backend.

## Backend and proxy configuration

Set `LINK_JDBC_URL`, `LINK_DB_USER`, `LINK_DB_PASSWORD`, `LINK_NODE_ID` for each backend.
Optionally set `LINK_REDIS_URI`. Keep credentials out of source control and command-line flags.
Every node ID must match its configured Velocity backend name and be unique while running.
The proxy uses the same SQL environment variables and the `link-velocity` shaded jar.
Configure Velocity modern forwarding and the matching backend secret; backend server ports
must only be reachable by trusted proxies. SQL credentials are trusted engine credentials.

Publish a valid template before native startup:

```powershell
.\gradlew.bat :link-core:publishTemplate -PtemplateName=lobby -PtemplateVersion=1 -PtemplateFile=C:/maps/lobby.slime
```

Backend JVM options:

```text
-Dlink.mode=journal
-Dlink.spawn.template=lobby
-Dlink.spawn.version=1
-Dlink.spawn.capacity=50
-Dlink.node.capacity=150
-Dlink.spawn.max-worlds=3
```

There is no vanilla fallback when template/database startup fails. Capacity exhaustion rejects
admission until a spare world/backend is ready; there is no durable waiting queue yet.

`link.admin` (OP by default) grants `/link templates|worlds|editor|publish|create|load|save|unload|delete`.
`/link create <name> <template> <version> <persistent|temporary>` clones a template.
`/link publish <editor> <template> <version>` publishes an immutable version.
The proxy `/warp <name>` requires `link.warp`; warp destinations are configured through the API.

## Plugin API

Compile against `io.linkmc:link-api:0.1.0-SNAPSHOT`, or the Link API which exports it. Do not
shade another copy. Obtain `io.linkmc.api.Link.api()` after server initialization. Never join
an asynchronous API future on the Minecraft thread.

```java
var link = io.linkmc.api.Link.api();
link.worlds().createEditor("lobby-editor");
// Await dependent operations before publishing/cloning.
link.worlds().publish("lobby-editor", "lobby", 2);
link.network().setWarp("shop", new io.linkmc.api.LinkApi.Destination(
    "node-a", "link-spawn", 10, 65, 10, 0, 0));
link.network().requestTravel(playerId, operationId, null); // null selects a spawn
link.network().travelStatus(operationId); // asynchronous status
link.engine().transact(() -> {
    // Bukkit inventory/block/entity mutations on the native main thread.
    // Completion means the containing frame committed; failure stops the backend.
});
```

Worlds exposes editor/publish/create/load/save/unload/delete/loaded/templates. Network exposes
channels, registerChannel, setChannelReady, reserve (optional exact destination), cancel,
warp/setWarp and travel request/status. Register channels only after their world is loaded and
mark them unavailable before unloading. Engine exposes journalEnabled and transact. Data
exposes read/commit/history for plugin-owned namespaces; writes to `player:` and `native:` are
rejected through the raw plugin facade. Sessions exposes low-level fenced ownership operations;
ordinary gameplay plugins should use travel rather than manipulating live engine leases.

PostgreSQL durable WAL, storage and replication determine the actual failure model. A local
SQL commit cannot guarantee survival of total database-host loss. Redis notifications may be
duplicated or reordered; consumers compare SQL revisions. Strict mode stays disabled until
runtime, recovery, performance and compatibility requirements are demonstrated.
