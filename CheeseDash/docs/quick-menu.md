[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](quick-menu.fr.md)

# Quick menu

The quick menu is the one piece of CheeseDash that runs in the game client: a small
screen of buttons, each one a command you saved, opened with a keybind. It exists for the
case the dashboard handles badly — you are standing in the world, you want to run
`/gamemode creative` or teleport to a build site, and alt-tabbing to a browser costs more
than the action is worth.

It is not an admin GUI. It cannot edit zones, read the audit log or do anything the
dashboard does. It sends command strings, and the server decides whether you may run
them.

Three small files:

| File | Lines | Role |
|---|---|---|
| `client/CheeseDashClient.java` | 50 | the client entrypoint and the keybind |
| `client/QuickMenuScreen.java` | 183 | the screen itself |
| `client/QuickMenuConfig.java` | 79 | reads and writes the shortcut file |
| `network/QuickMenuPayload.java` | 26 | the one client-to-server packet |

---

## Installing and opening it

Nothing to install beyond CheeseDash itself. The mod's `fabric.mod.json` declares
`"environment": "*"` with both a `main` and a `client` entrypoint, so the same jar is the
server mod and the client mod. Drop it in the client's `mods/` folder next to Fabric API
and the keybind appears.

**The keybind is unbound by default.** `CheeseDashClient` registers it with
`GLFW_KEY_UNKNOWN`, so nothing happens until you bind it: *Options → Controls → Key
Binds*, category **CheeseDash**, entry **Open CheeseDash Quick Menu** (in French,
*Ouvrir le menu rapide CheeseDash*). That is deliberate — an admin tool that grabs a key
on first launch is an admin tool that eats a keystroke someone else wanted.

Pressing the key again while the menu is open closes it, handled twice over: the tick
handler toggles it, and the screen's own `keyPressed` closes on the same key. `Escape`
closes it like any screen.

> The game **does not pause** while the menu is open (`shouldPause()` returns `false`).
> On a single-player world the world keeps ticking behind the panel. On a server that is
> the only possible behaviour anyway.

### Who can open it

```java
if (!client.player.hasPermissionLevel(2)) continue;
```

The client checks the player's permission level before opening the screen, and only
operators at level 2 or above get it. For anyone else the key does nothing at all: no
screen, no message, no sound. That is not a security control — a client-side check never
is, and a modified client could open the screen anyway — it is there so that a
non-operator who happens to have the mod installed does not find a menu whose every
button fails.

The real check is on the server, below.

---

## Using it

The screen is a centred panel, 300 pixels wide, in the dashboard's colours: a dark
`#131620` panel with a `#252836` border, an amber title, over a translucent black
backdrop. Shortcuts are laid out in **two columns**, each one a button carrying your
label plus a narrow `✕` to its right that deletes it with no confirmation.

With no shortcuts configured the panel reads:

```text
No shortcuts configured. Press '+' to add one.
```

```text
Aucun raccourci configuré. Appuyez sur '+' pour en ajouter un.
```

Below the grid sits **+ Add a shortcut**. It replaces the panel with a two-field form:

| Field | Max length | Placeholder |
|---|---|---|
| label | 64 characters | `Shortcut name...` |
| command | 256 characters | `/command...` |

**Add** saves and returns to the grid; **Cancel** returns without saving. Both fields
are trimmed and both must be non-empty — an empty one makes **Add** do nothing, with no
error shown. The label field takes focus when the form opens, so you can type straight
away.

Clicking a shortcut sends its command to the server and closes the menu immediately. The
screen does not wait for a result and shows no feedback: whatever the command produces,
you read it in chat after the menu is gone.

The leading slash is optional. The server runs the string through
`executeWithPrefix`, which accepts a command with or without it, so `gamemode creative`
and `/gamemode creative` both work.

---

## Configuration

Client-side, in `config/cheesedash_shortcuts.json` **of the client instance** — not the
server's config directory. Nothing in the dashboard reads or writes it, and it is per
machine, not per account.

```json
{
  "shortcuts": [
    { "label": "Creative",  "command": "/gamemode creative" },
    { "label": "Survival",  "command": "/gamemode survival" },
    { "label": "Day",       "command": "/time set day" },
    { "label": "Spawn",     "command": "/tp @s 0 100 0" },
    { "label": "Heal",      "command": "/effect give @s minecraft:instant_health 1 10" }
  ]
}
```

Two fields per entry, both strings, both required. An entry missing either, or with
either one empty, is **skipped on load** — silently. A file that fails to parse at all is
also ignored silently, and then **overwritten the next time you add or remove a
shortcut**, which loses everything that was in it. If you hand-edit the file, validate
the JSON before opening the menu again.

The file is re-read every time the menu opens (`config.load()` in the tick handler), so
an external edit shows up without restarting the game. It is rewritten in full on every
add and every delete. Order in the file is the order on screen, filling left to right,
two per row, so hand-editing is how you reorder shortcuts — the screen offers no way to
move them.

---

## What happens on the server

Clicking a shortcut sends a `cheesedash:quick_menu_cmd` custom payload carrying one
string. The receiver is registered in `CheeseDashMod.onInitialize`, on the main
entrypoint, so it is active on any server running CheeseDash whether or not anyone has
the client half:

```java
ServerPlayNetworking.registerGlobalReceiver(QuickMenuPayload.ID, (payload, context) -> {
    ServerPlayerEntity player = context.player();
    String command = payload.command();
    if (!player.hasPermissionLevel(2)) {
        LOGGER.warn("[CheeseDash] QuickMenu: {} tried to execute '{}' without permission",
            player.getName().getString(), command);
        return;
    }
    context.player().server.execute(() -> {
        LOGGER.info("[CheeseDash] QuickMenu: {} executing '{}'",
            player.getName().getString(), command);
        player.server.getCommandManager().executeWithPrefix(player.getCommandSource(), command);
    });
});
```

Four things to take from that.

**The permission check is server-side and it is the one that counts.** A non-operator who
forges the packet gets nothing but a `WARN` line naming them and the command they tried:

```text
[CheeseDash] QuickMenu: Griefer tried to execute '/op Griefer' without permission
```

**The command runs as the player, not as the console.** The command source is
`player.getCommandSource()`, so `@s` is the player, relative coordinates resolve from
where they stand, and the command carries their permission level. An operator cannot
reach anything through the quick menu that they could not type in chat. This is the
opposite of `POST /api/v1/server/command`, which runs as the console.

**Execution is handed to the server thread** with `server.execute(...)`, because the
packet arrives on a netty thread and touching the game from there is how you corrupt a
world.

**It is logged, not audited.** Both the refusal and the execution go to the server log at
`WARN` and `INFO`. Nothing reaches `config/cheesedash_audit.json`, so an operator's quick
menu activity is invisible in the dashboard's audit view — see
[audit log](audit.md#what-is-not-recorded). To review who ran what from the menu, grep
the server log:

```bash
grep "QuickMenu" logs/latest.log
```

### No validation of the command string

The payload is a plain string up to the 256 characters the text field allows, and the
server passes it to the command dispatcher as-is. There is no allowlist, no length check
on the server side, and no parsing before execution. That is not a privilege escalation —
the dispatcher enforces each command's own permission level against the player's — but it
does mean the packet is a general-purpose "run this command as me" primitive for anyone
already at permission level 2. An operator account that gets compromised can do through
this packet whatever it could do in chat, and the only trace is the server log.

---

## Uninstalling the client half

Remove the jar from the client's `mods/` folder, or leave it: with no shortcuts
configured and no key bound, the client half costs one registered keybind and nothing
else. `config/cheesedash_shortcuts.json` is left behind on disk and is yours to delete.

The server keeps listening for the packet as long as CheeseDash is installed server-side,
which is harmless — a client that does not send it changes nothing, and a client that
does is checked.

---

← [Audit log](audit.md) · [Architecture](architecture.md) →
