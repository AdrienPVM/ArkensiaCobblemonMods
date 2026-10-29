[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CheeseKart runs on the server and on the client, and which pieces matter depends on
which side you are installing it on. Its `fabric.mod.json` declares
`"environment": "*"`, so Fabric loads it on both.

---

## What you need

### Server

| | Version | Why |
|---|---|---|
| Minecraft | **1.21.1** exactly | declared as `~1.21.1` |
| Fabric Loader | ≥ 0.16.0 | |
| Fabric API | any 1.21.1 build | commands, networking, lifecycle events |
| Java | 21 or newer | |
| [CheeseDash](../../CheeseDash/) | any | **hard dependency** — the server refuses to start without it |

### Client, for each racer

| | Required? | Without it |
|---|---|---|
| CheeseKart | no, but recommended | boats stay visible, and Shift genuinely dismounts the player from a locked kart — the server re-mounts them a tick later, so it reads as a stutter rather than as a key that does nothing |
| [OpenBoatUtils](https://modrinth.com/mod/openboatutils) | **effectively yes** | the kart handles exactly like a vanilla boat — no grip, no acceleration, no steering changes |

A racer without OpenBoatUtils is not an error anybody will see. They just drive a normal
boat while everyone else drives a kart, and nothing is logged. If racing is a real part
of your server, put OpenBoatUtils in the client pack and treat it as mandatory.

### Optional

- [CobbleShift](../../CobbleShift/) — enables the `species` field and argument, turning
  racers into Pokémon for the duration of a session. Reached by reflection, so it can be
  added or removed at any time.

---

## Build

CheeseKart compiles against CheeseDash's built jar, so **CheeseDash has to be built
first**:

```bash
cd CheeseDash  && ./gradlew build     # gradlew.bat on Windows
cd ../CheeseKart && ./gradlew build
```

From the repository root, `tools/build-all.sh` does this in the right order for every
mod at once.

The jar you want is:

```text
CheeseKart/build/libs/cheesekart-1.0.0+1.21.1.jar
```

Ignore `cheesekart-1.0.0+1.21.1-sources.jar` — it is the source bundle, not a mod.

A compile error naming missing `com.cheesedash` classes means CheeseDash was not built
first.

## Install

1. Stop the server.
2. Copy the CheeseDash jar and the CheeseKart jar into `mods/`.
3. Make sure Fabric API is there too.
4. Start the server.

Give each racer's client the CheeseKart jar and OpenBoatUtils.

---

## What the first launch creates

One file:

```text
config/cheesekart.json
```

It is written with three presets — `rally`, `drift` and `parkour` — because the mod
seeds defaults whenever it finds no presets at all. Full field reference in
[configuration](configuration.md).

Nothing else is created. No database, no world data, no player files. Sessions live in
memory and are deliberately not persisted.

## Verifying it loaded

Three lines in the server log, in this order:

```text
[CheeseKart] Initializing...
[CheeseKart] Initialized
[CheeseKart] Server started — 3 presets loaded
```

The first two come from mod init, the third from the server-started event — so if you
see the first two and not the third, the server failed to finish starting for an
unrelated reason.

Expect one more line, depending on whether OpenBoatUtils is installed **on the server**:

```text
[CheeseKart] OBU detected — using OBU BytePayload via reflection
[CheeseKart] OBU not loaded — registered fallback payload channel
```

Both are normal. [Why there are two paths](physics.md#why-the-payload-is-sent-two-different-ways).

And one about CobbleShift, depending on whether it is installed:

```text
[CheeseKart] CobbleShift integration active
[CheeseKart] CobbleShift not installed — transform features disabled
```

Then confirm in game, as an operator:

```mcfunction
/cheesekart presets
```

```text
--- Presets (3) ---
  parkour — Parkour | slip=0.98 step=2.0 fwd=0.04 [locked]
  rally — Rally | slip=0.98 step=1.25 fwd=0.04 [locked]
  drift — Drift | slip=1.1 step=1.25 fwd=0.06 [locked]
```

If the command does not exist, either the mod did not load or you are below permission
level 2 — there is no separate permission message: Brigadier hides the whole tree, so
both cases give *Unknown or incomplete command*. The log tells them apart.

Finally, the dashboard: `kart` should appear in `GET /api/v1/modules`, and the sidebar
should show a 🏎 entry.

---

## Uninstalling

1. Run `/cheesekart stopall` **before stopping the server.** This matters: it dismounts
   every racer, removes the invisible boats, and resets client physics. Pull the jar
   with sessions running and you leave invisible invulnerable boats in the world.
   Stopping the server normally also runs `stopAll`, so a clean shutdown is enough — it
   is an unclean one you have to worry about.
2. Stop the server.
3. Remove the jar from `mods/`.
4. Optionally delete `config/cheesekart.json`. Keeping it costs nothing and preserves
   your presets if you reinstall.

Nothing is left in world data or player data. If you did lose a boat somewhere, it is a
plain vanilla boat entity with the invisible flag — findable with `/kill @e[type=boat]`
in the area, which will also take out legitimate boats, so aim carefully.

---

← [CheeseKart](../README.md) · [Getting started](getting-started.md) →
