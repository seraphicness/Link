# Link 26.3 — implementation status

This branch contains a **development foundation, not the complete requested gameplay engine**.
It must not be deployed with valuable player inventories. Strict mode intentionally refuses
startup. Default mode is disabled and retains upstream ASP behavior.

## Implemented and independently testable

- `link-api`: Java 25, Minecraft-independent asynchronous plugin contracts; server API
  transitively exports this module. Do not shade another copy of it into a plugin.
- PostgreSQL after-state event log and current snapshots, atomically committed with an outbox.
  Aggregate revisions use optimistic concurrency; multi-aggregate writes commit together.
- Operation UUID plus canonical SHA-256 request digest. Identical retries return the original
  result, while reusing an ID with different content is rejected.
- DB-clock player/node leases, epoch fencing, prepare/accept transfer state machine. Prepared
  transfers freeze source writes. The destination and final player revision are stored in SQL.
- SQL channel reservations enforce per-channel and per-node capacities, including in-flight
  arrivals. Old admission is released only when the new owner admits the player.
- Immutable versioned SRF template blobs with integrity checks; SQL warp destinations.
- At-least-once Redis Stream outbox relay. Redis messages are hints, not state or ownership.
  Delivery may be duplicated or out of order; consumers reread SQL versions.
- A world lifecycle API and native ASP adapter: editor world, template publish, clone,
  explicit checkpoint, load, unload and tombstone delete. Temporary clones never overwrite
  published templates. Persistent checkpoints are node-scoped and capped at 16 MiB by the
  current event store. These checkpoints are NOT atomic with native player state.
- NMS bootstrap/shutdown feature patch and a Paper world-loader patch that skips vanilla
  secondary dimensions in tooling mode. Server artifact branding is Link.
- A bounded spawn provisioner component, not yet a running network controller.

## Not implemented / not claimed

- Native player.dat, statistics, advancements and all other player state replacement.
- Transactional NMS click/drag/cursor/crafting/merchant/hopper/item-entity/block/health/tick
  mutation interception, packet release after commit, and reconciliation after uncertain commits.
- Shared commit/recovery boundary between world and player state.
- Velocity plugin, authenticated arrival-ticket transport and actual first-spawn placement.
- Always-on channel provisioning, idle cleanup, global /spawn and /warp commands.
- User-facing world rule configuration, region permissions, template metadata/version migration.
- Whole-network restart reconciliation, cancelled/stranded transfer recovery, player-data
  import, backup/PITR restore procedure, audit retention, Redis trimming, production metrics.
- Transparent compatibility with plugins mutating NMS or mutable ItemStacks directly.

The existence of a public interface or a core component is not evidence of native integration.
No claim of zero data loss or duplication is made. Strict mode remains disabled until these
paths are implemented and crash-tested end to end.

## Build and tests

Requires Java 25 and PostgreSQL 17 for integration tests. Redis 7 is used for relay tests.
All `LINK_TEST_*` variables must point to a **disposable** database: tests truncate Link tables.

```powershell
$env:LINK_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55432/link_test'
$env:LINK_TEST_DB_USER='link_test'
$env:LINK_TEST_DB_PASSWORD='link_test_local'
$env:LINK_TEST_REDIS_URI='redis://127.0.0.1:56379'
.\gradlew.bat :link-core:test :link-api:jar
.\gradlew.bat applyAllPatches
.\gradlew.bat :aspaper-server:compileJava
```

Do not combine `applyPaperSingleFilePatches` and `applyAllServerPatches` in one Gradle invocation:
the upstream build currently has an implicit dependency validation issue for that combination.
On Windows, long paths must be enabled for Git in generated resource repositories as well.

The PostgreSQL tests exercise duplicate operations, stale ownership, simultaneous writers,
atomic rollback on an outbox failure, replay, transfer fencing, concurrent capacity reservation,
cross-node admission transfer, immutable templates and duplicate Redis notification delivery.
They do not constitute a Minecraft runtime or chaos test.

## Tooling mode only

Set `LINK_JDBC_URL`, `LINK_DB_USER`, `LINK_DB_PASSWORD`, `LINK_NODE_ID` in the environment.
No secrets belong in the repository, JVM command line or logs. Ensure the JDBC role can create
the initial schema; production privilege separation and migration management remain pending.

Publish an existing valid `.slime` template offline:

```powershell
.\gradlew.bat :link-core:publishTemplate -PtemplateName=lobby -PtemplateVersion=1 -PtemplateFile=C:/maps/lobby.slime
```

Then start the built server with `-Dlink.mode=tools -Dlink.spawn.template=lobby
-Dlink.spawn.version=1`. The template must exist and decode successfully. Boot failures never
fall back to a vanilla spawn. A node ID must be unique; a live DB lease rejects duplicate nodes.
Tooling mode uses upstream player files and is **not** the requested durable gameplay mode.
Use explicit API saves while editing; current world checkpoints are not per-tick persistence.

Tooling commands require `link.admin` (operators by default):

```text
/link templates
/link worlds
/link editor <world>
/link publish <editor> <template> <version>
/link create <world> <template> <version> <persistent|temporary>
/link load <world>
/link save <world>
/link unload <world>
/link delete <unloaded-world>
```

Deletion writes a tombstone and retains prior checkpoint events. It is not a rollback of
player-owned items. Do not restore a world checkpoint independently of player state in a
production economy. `/spawn` and `/warp` gameplay commands are not provided yet.

`-Dlink.mode=strict` deliberately throws before worlds load. Do not remove this guard to
pretend native transactions have been implemented.

## Plugin API

Compile against `io.linkmc:link-api:0.1.0-SNAPSHOT` (publish locally with
`:link-api:publishToMavenLocal`), or against the Link server API which exports it. Obtain
`io.linkmc.api.Link.api()` after server initialization. All API calls return CompletionStage;
never block the tick thread with `join()`/`get()`. Completion callbacks are not tick-thread
callbacks; dispatch Bukkit mutations back through the server scheduler.

```java
var link = io.linkmc.api.Link.api();
link.worlds().createEditor("lobby-editor");
// After the editor has been built, publish an immutable version:
link.worlds().publish("lobby-editor", "lobby", 2);
link.worlds().create("arena-1", "lobby", 2, false);
link.network().setWarp("shop", new io.linkmc.api.LinkApi.Destination(
    "node-a", "link-spawn", 10, 65, 10, 0, 0));
```

Examples are independent operations; await a dependent operation's completion before starting
the next. Raw `data()` is a trusted low-level service, not a Bukkit inventory API or plugin
security sandbox. Plugin authors must namespace their records and supply session guards for
player records. Only a future native adapter may interpret native state payloads.

## Durability boundary

SQL success is the commit boundary. Configure PostgreSQL durable storage, WAL, backups and
synchronous replication according to the intended failure model; a local commit alone is not
a guarantee against losing an entire DB host. Never retry an uncertain operation with a new
UUID. Retain the original payload and UUID, then resubmit or query the original event.

Session and revision checks provide safety at the database boundary. They do not automatically
freeze old Minecraft simulation or client packets. That requires the unimplemented NMS hooks.
DB/Redis failures must not fall back to player files or a second independent writer.
