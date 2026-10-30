CREATE TABLE IF NOT EXISTS link_schema (version integer PRIMARY KEY);
INSERT INTO link_schema VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS link_sessions (
 player uuid PRIMARY KEY, node text NOT NULL, epoch bigint NOT NULL,
 state text NOT NULL CHECK (state IN ('ACTIVE','MOVING','RELEASED')),
 expires_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS link_operations (
 id uuid PRIMARY KEY, digest bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS link_state (
 id text PRIMARY KEY, version bigint NOT NULL, schema_version integer NOT NULL,
 payload bytea NOT NULL
);
CREATE TABLE IF NOT EXISTS link_events (
 aggregate_id text NOT NULL, version bigint NOT NULL, operation uuid NOT NULL REFERENCES link_operations(id),
 schema_version integer NOT NULL, type text NOT NULL, payload bytea NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (aggregate_id, version)
);
CREATE TABLE IF NOT EXISTS link_outbox (
 id uuid PRIMARY KEY REFERENCES link_operations(id), delivered boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS link_transfers (
 id uuid PRIMARY KEY, player uuid NOT NULL REFERENCES link_sessions(player), source_node text NOT NULL,
 source_epoch bigint NOT NULL, destination_node text NOT NULL, world text NOT NULL,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL, final_version bigint NOT NULL,
 state text NOT NULL CHECK (state IN ('PREPARED','ACCEPTED')), accepted_epoch bigint,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX IF NOT EXISTS link_one_prepared_transfer ON link_transfers(player) WHERE state='PREPARED';
CREATE TABLE IF NOT EXISTS link_nodes (
 id text PRIMARY KEY, capacity integer NOT NULL CHECK(capacity > 0),
 heartbeat timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS link_channels (
 id text PRIMARY KEY, node text NOT NULL REFERENCES link_nodes(id), world text NOT NULL,
 capacity integer NOT NULL CHECK(capacity > 0), ready boolean NOT NULL DEFAULT false,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL, UNIQUE(node,world)
);
CREATE TABLE IF NOT EXISTS link_reservations (
 id uuid PRIMARY KEY, player uuid NOT NULL, channel text NOT NULL REFERENCES link_channels(id),
 state text NOT NULL CHECK(state IN ('RESERVED','ADMITTED')), expires_at timestamptz NOT NULL
);
ALTER TABLE link_reservations DROP CONSTRAINT IF EXISTS link_reservations_player_key;
CREATE UNIQUE INDEX IF NOT EXISTS link_one_reservation ON link_reservations(player) WHERE state='RESERVED';
CREATE UNIQUE INDEX IF NOT EXISTS link_one_admission ON link_reservations(player) WHERE state='ADMITTED';
CREATE TABLE IF NOT EXISTS link_warps (
 name text PRIMARY KEY, node text NOT NULL, world text NOT NULL,
 x double precision NOT NULL, y double precision NOT NULL, z double precision NOT NULL,
 yaw real NOT NULL, pitch real NOT NULL
);
CREATE TABLE IF NOT EXISTS link_templates (
 name text NOT NULL, version integer NOT NULL CHECK(version > 0), payload bytea NOT NULL,
 sha256 bytea NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(name,version)
);
