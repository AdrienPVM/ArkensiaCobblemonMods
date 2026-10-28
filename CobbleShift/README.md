# CobbleShift

> Turn players into Pokémon, with passive abilities — plus two minigames built on top.

Players (or NPCs) take the form of a Pokémon species, inherit a passive ability tied
to its type, and can be scaled and sped up. Two minigames use the mechanic:
**CobbleHunt** (a prop-hunt where hiders are Pokémon) and **Hot Potato**.

**Minecraft 1.21.1 · Fabric · Java 21 · 30 files, ~5 700 lines**

---

## Requirements

- Fabric Loader ≥ 0.16.0, Fabric API, Java 21
- **Cobblemon** — optional. Reached through reflection (`CobblemonHook`), never
  imported directly, so the mod loads cleanly without it and degrades gracefully.
- **CheeseDash** — optional, enables the admin panel

## Commands

All under `/cshift`:

| Subcommand | Purpose |
|------------|---------|
| `transform <species>` · `revert` | transform yourself |
| `transformnpc` · `revertnpc` · `revertallnpc` | the same for NPCs |
| `revertall` | revert every transformed player |
| `ability <name>` | set the passive ability |
| `scale` · `scalenpc` · `speed` · `speednpc` | size and movement tuning |
| `species` · `list` | inspection |
| `enable` · `disable` · `toggle` · `global` | master switches |

## Configuration

`config/cobbleshift_npc_transforms.json` holds NPC transformations so they survive a
restart.

## Dashboard

Registers under the `shift` prefix: active transforms, abilities, species list,
configuration, and the Hot Potato toggle.

## A note for contributors

The Cobblemon integration is reflection-based on purpose. If you touch it, **never
look a method up by its Yarn name** — method names are intermediary at runtime, so
`getDeclaredMethod("someYarnName", ...)` works in dev and fails in production. Scan by
signature instead:

```java
for (Method m : clazz.getDeclaredMethods()) {
    if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == Text.class) { ... }
}
```

CheeseGuard can drive this mod during events through four effect types
(`TRANSFORM_ALL`, `TRANSFORM_RANDOM`, `TRANSFORM_BY_TEAM`, `REVERT_ALL`).

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
