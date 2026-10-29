[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

Everything lives under `config/cobblemon_dungeons/`, created on first launch:

```text
config/cobblemon_dungeons/
├── config.json              main config — the database block, and nothing else
├── dungeon_tickets.json     ticket templates
├── dungeons/
│   └── example_dungeon.json one file per dungeon, written on first launch
├── trainers/
│   └── example_trainer.json one file per Guardian trainer, written at first server start
├── run_history.json         appears after the first finished run
├── cooldowns.json           appears after the first successful run
└── leaderboard.json         appears after the first successful run
```

The path comes from `FabricLoader.getInstance().getConfigDir().resolve("cobblemon_dungeons")`,
so it follows the server's config directory wherever that is.

This page covers `config.json` and the reload semantics. The dungeon files have their own
page — [the dungeon definition](dungeons.md) — as do [tickets](tickets.md) and
[trainers](trainers.md), because each is large enough to deserve one.

---

## `config.json`

One section. The mod has no behavioural settings outside the per-dungeon files: there is no
global toggle, no debug flag that survives a restart, no tuning knob here.

```json
{
  "database": {
    "useSql": false,
    "host": "localhost",
    "port": 3306,
    "database": "minecraft",
    "username": "root",
    "password": "password",
    "tablePrefix": "cobblemon_dungeons_"
  }
}
```

| Key | Type | Default | What it does |
|---|---|---|---|
| `database.useSql` | boolean | `false` | Master switch for the MariaDB connection |
| `database.host` | string | `"localhost"` | Database host |
| `database.port` | int | `3306` | Database port |
| `database.database` | string | `"minecraft"` | Schema name |
| `database.username` | string | `"root"` | Database user |
| `database.password` | string | `"password"` | Database password |
| `database.tablePrefix` | string | `"cobblemon_dungeons_"` | Prefix for the table this mod creates |

⚠️ `password` ships as the literal string `"password"`. It is the field initialiser on
`MainConfig.DatabaseConfig.password` in
`src/main/java/com/sebastian/cobblemondungeons/config/MainConfig.java`, and it is written
verbatim into `config.json` the first time the server starts. It is a placeholder, not a
secret, and it must be replaced with your real database password — or left alone only
because `useSql` is `false` and no connection is ever attempted. Do not put this server
into service with the default value and `useSql: true`.

Two more things about that block worth saying out loud. The file is plain text in the
config directory, so anyone who can read your config can read the password; keep the
database user scoped to the one schema it needs. And `GET /api/v1/dungeons/config` masks
`password` as `***` but leaves `host`, `port`, `database` and `username` visible to any
authenticated dashboard session.

### What the database is actually for

Exactly one thing: carrying a player's serialised state between a main server and a
dungeon server in `CROSS_SERVER` mode. On init the mod creates one table:

```sql
CREATE TABLE IF NOT EXISTS cobblemon_dungeons_player_sync (
  uuid VARCHAR(36) PRIMARY KEY,
  data MEDIUMBLOB,
  last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
)
```

`data` is a GZip-compressed NBT compound. Nothing else is stored — run history,
leaderboards and cooldowns are JSON files regardless of this setting.

With `useSql: true` the Cobblemon PC is written to the database and stripped from the
transfer payload, because a full PC is too large to push through a plugin message. With
`useSql: false` the payload carries everything, PC included, which works for ordinary
PC sizes and is the supported single-server path.

### Connection behaviour

HikariCP, with the MariaDB driver (`org.mariadb.jdbc.Driver`, MySQL-compatible), a pool of
10 connections and a 5-second connection timeout. Both HikariCP and the driver are bundled
into the jar, so nothing has to be installed alongside.

A failure at startup is not fatal: the error is logged as *Failed to initialize database.
Player data transfer via SQL will be disabled.*, the pool is discarded, and the mod runs as
though `useSql` were false. `/dungeons sql test` reports the stored error message.

The connection is opened once in `onInitialize` and closed on `SERVER_STOPPING`. **Editing
this section requires a server restart** — nothing re-reads it, and the dashboard's reload
route rebuilds the `MainConfig` object in memory without touching the live pool.

## Server role

The one setting that is not in any config file. `cobblemon.dungeons.serverRole` as a JVM
system property, or `COBBLEMON_DUNGEONS_SERVER_ROLE` as an environment variable:

```bash
java -Dcobblemon.dungeons.serverRole=dungeon -Xmx8G -jar fabric-server-launch.jar nogui
```

The value `dungeon`, case-insensitively, marks a dungeon server. Anything else — including
an unset variable — marks a main server. The property is read first and the environment
variable only as a fallback.

The result is cached in a `volatile Boolean` on the first call and logged once:

```text
Cobblemon Dungeons server role: dungeon
```

Changing it needs a restart. What the role changes is documented in
[the instance lifecycle](instances.md#cross-server-runs): on a dungeon server `/dungeons`
and the whole `/DungeonsParty` tree are refused, and entity cleanup is unrestricted rather
than selective.

On a single-server setup, leave it unset.

## What reloads and what does not

| Changed | Picked up by |
|---|---|
| `dungeons/*.json` | `POST /api/v1/dungeons/configs/reload`, or a restart |
| `dungeon_tickets.json` | The same reload for the server; connected clients only on their next join |
| `run_history.json`, `cooldowns.json`, `leaderboard.json` | The same reload — but a run finishing first will overwrite your edit |
| `trainers/*.json` | **Restart only.** `TrainerLoader.load` runs on `SERVER_STARTED` and nothing re-runs it |
| `config.json` | **Restart only** for the database connection |
| Server role | **Restart only** |

The reload path is `ConfigLoader.load()`. It clears the dungeon registry first, so a
dungeon whose file you deleted disappears, and a renamed file re-registers under whatever
`general.id` is inside it. Live instances are unaffected — each holds the
`DungeonConfig` object it was built with, so a reload mid-run does not change the rules
under a party's feet.

## A complete annotated example

JSON has no comments, so the annotations below are prose in a `// …` form you must strip
before use. This is the main config with the database switched on:

```json
{
  "database": {
    // Turn this on only for a real multi-server setup behind a proxy.
    // On a single server, leave it false: the join payload carries everything.
    "useSql": true,

    // Reachable from every Minecraft server that will run dungeons.
    "host": "10.0.0.12",
    "port": 3306,

    // The schema must already exist; the mod creates its table inside it,
    // not the schema itself.
    "database": "cacademy",

    // Give this user rights on this schema only.
    "username": "dungeons",

    // Replace the shipped "password" placeholder. This is the one line
    // that must not keep its default.
    "password": "a-real-password-from-your-password-manager",

    // Prefix for the single table created: <prefix>player_sync.
    // Change it only if two installs share one schema.
    "tablePrefix": "cobblemon_dungeons_"
  }
}
```

For a single-server install the whole file can stay exactly as generated — `useSql: false`
and the rest ignored. That is the configuration most servers want, and the placeholder
password is harmless in it.

A full annotated dungeon file is in [the dungeon definition](dungeons.md); the generated
`dungeons/example_dungeon.json` is itself a worked example with every recent field
populated, and reading it next to that page is the fastest way to learn the format.

---

← [Commands](commands.md) · [The dungeon definition](dungeons.md) →
