# cobblewarden_boss — patches

Quatre patches appliqués au JAR `cobblewarden_boss-fabric-1.0.0.jar`.

## Patch 1 — Relaxation des contraintes de version

Le `fabric.mod.json` déclarait des dépendances trop strictes alors que le bytecode
n'utilise que des APIs déjà présentes dans les versions plus anciennes installées.

### Analyse (bytecode)

APIs Cobblemon réellement référencées :

- `com.cobblemon.mod.common.entity.pokemon.PokemonEntity.getPokemon()`
- `com.cobblemon.mod.common.pokemon.Pokemon.getPersistentData()`

Les deux existent dans Cobblemon 1.6.1.

**Aucune référence à `teamresourceful.fightorflight.*`** dans le bytecode — la
dépendance sert probablement de soft-requirement de contexte (boss en combat
sauvage), mais ne touche à aucune classe au runtime.

### Changements appliqués (bloc `depends`)

| Clé | Original | Patched |
|-----|----------|---------|
| fabricloader | `>=0.17.3` | `>=0.16.0` |
| cobblemon | `>=1.7.1` | `>=1.6.1` |
| fightorflight | `>=0.10.2` | `>=0.9.0` |

## Patch 2 — Correction du trou noir à Warden (hasSpawned branch)

### Symptôme

Après un premier boss Pokémon spawné dans une région, **tous les wardens
suivants disparaissent sans rien laisser** — ni warden, ni boss. Observé dans les logs :

```
[Server thread/INFO]: Warden spawn detected at position: class_2338{x=-870, y=-51, z=1058}
[Server thread/INFO]: This location has already spawned a boss. Cancelling Warden spawn.
```

### Cause racine

`WardenSpawnInterceptor.lambda$register$1` (listener `EntityEvent.ADD`) :

```java
if (AncientCityTracker.hasSpawned(level, pos)) {
    LOGGER.info("…Cancelling Warden spawn.");
    return EventResult.interruptFalse();   // ← BUG : cancel warden sans remplacement
}
```

`AncientCityTracker.hasSpawned()` utilise une clé grossière `(x/128)_(y/64)_(z/128)`
qui marque toute la région 128×64×128 blocs comme "déjà spawnée" à vie. Un boss
tué il y a longtemps gèle tous les wardens futurs dans cette zone.

### Fix bytecode

Changement 1 byte dans `WardenSpawnInterceptor.class`, méthode `lambda$register$1`,
à l'offset de l'instruction `invokestatic` suivant le `ldc "…Cancelling…"` :

```
Avant : B8 00 B1   (invokestatic #177 → EventResult.interruptFalse)
Après : B8 00 D2   (invokestatic #210 → EventResult.pass)
```

Comportement final :

| Situation | Avant | Après |
|-----------|-------|-------|
| 1er warden dans région | cancelled + boss spawné | cancelled + boss spawné (identique) |
| Warden suivant dans région | **cancelled, rien ne spawne (trou noir)** | **spawne normalement** |
| Non-warden | pass | pass |

Le constant pool index `#210 = EventResult.pass()` est déjà présent (utilisé à
l'offset 114 pour les entités non-warden), donc pas besoin d'éditer la
constant pool.

## Rejouer les patches

Depuis la racine `CheeseMods/` :

```bash
# 1. Backup du JAR original
cp "<server>/mods/cobblewarden_boss-fabric-1.0.0.jar" \
   temp/cobblewarden_boss-fabric-1.0.0.original.jar

# 2. Injecter le fabric.mod.json patché (Patch 1)
mkdir -p temp/_cwpatch && cp CheeseCompatModsFix/patches/cobblewarden/fabric.mod.json temp/_cwpatch/
cd temp/_cwpatch && jar uf "<server>/mods/cobblewarden_boss-fabric-1.0.0.jar" fabric.mod.json && cd -

# 3. Injecter la classe patchée (Patch 2)
cd CheeseCompatModsFix/patches/cobblewarden/classes && \
    jar uf "<server>/mods/cobblewarden_boss-fabric-1.0.0.jar" com/ace/cobbleboss/WardenSpawnInterceptor.class && cd -

# 4. Nettoyage
rm -rf temp/_cwpatch
```

## Regénérer le Patch 2 from scratch

```python
# Pattern dans WardenSpawnInterceptor.class :
# getstatic #54 (LOGGER) + ldc #171 ("This location…") + invokeinterface #62,2 + invokestatic #177 + areturn
pat = bytes([0xB2,0x00,0x36, 0x12,0xAB, 0xB9,0x00,0x3E,0x02,0x00, 0xB8,0x00,0xB1, 0xB0])
rep = bytes([0xB2,0x00,0x36, 0x12,0xAB, 0xB9,0x00,0x3E,0x02,0x00, 0xB8,0x00,0xD2, 0xB0])
with open('WardenSpawnInterceptor.class','rb') as f: data = f.read()
assert data.count(pat) == 1
open('WardenSpawnInterceptor.class','wb').write(data.replace(pat, rep))
```

Les constant pool indices (#54, #171, #62, #177, #210) sont spécifiques à ce
build du mod. Si le mod est mis à jour, il faudra recalculer le pattern via
`javap -v WardenSpawnInterceptor.class` et localiser les nouveaux indices pour
`LOGGER`, `"This location…"`, `Logger.info`, `EventResult.interruptFalse`, et
`EventResult.pass`.

## Patch 3 — Remplacement de `BossSpawner` (API Cobblemon directe)

### Symptôme

Avec le mod `cobblemon_spawn_alerts` installé, tout spawn de warden fait crasher
le serveur avec une NPE dans `PokemonSpawnCommandMixin` — déclenchée par l'appel
`/pokespawnat` qu'utilise l'original `BossSpawner` pour invoquer le boss.

### Cause racine

L'original `BossSpawner.spawnBoss()` construit une commande `/pokespawnat <x> <y> <z> <props>`
et l'exécute via `server.getCommandManager().executeWithPrefix(source, cmd)`.
La commande est interceptée par `cobblemon_spawn_alerts` qui tente d'accéder à
un contexte de source absent et déréférence un null.

Demande explicite de l'utilisateur : **ne pas modifier `cobblemon_spawn_alerts`**.
La correction doit être portée du côté `cobblewarden_boss`.

### Fix — remplacement complet de la classe

`BossSpawner.java` est réécrit pour appeler l'API Cobblemon directement via
reflection, évitant le détour par la commande :

- `PokemonProperties.Companion.parse(propsString)` — construit les Pokemon props
- `props.createEntity(level)` — instancie l'entité sans passer par command dispatch
- `serverLevel.spawnEntity(entity)` — spawn direct

La reflection est utilisée parce que `CheeseCompatModsFix` compile avec
`compileOnly files(...Cobblemon.jar)` (pas `modCompileOnly`), donc les classes
intermédiaires de Minecraft référencées par l'API Cobblemon ne sont pas
résolvables à la compilation. Même pattern que `BattleTowerDisconnectFix`,
`RadGymsFix`, `SafariBallFix`.

Les deux overloads publics sont préservés (`spawnBoss(World, BlockPos)` et
`spawnBoss(World, BlockPos, String)`), consommés par `WardenSpawnInterceptor`
et `PortalAnimator`. La sélection d'espèce (giratina en deep_dark Y<0, sinon
exploud) et les props de combat sont identiques à l'original.

**Agro auto (v1.2.24)** : après `spawnEntity`, on récupère le joueur le plus
proche dans un rayon de 64 blocs via `serverLevel.getClosestPlayer(...)` et on
appelle `mob.setTarget(player)` si l'entité est un `MobEntity`. Raison : la
config `fightorflight.do_pokemon_attack_unprovoked=false` empêche l'agressivité
spontanée, mais `setTarget` est une cible dirigée (pas "unprovoked") donc
l'IA de FightOrFlight bascule en mode combat dès le spawn. Log attendu :
`Boss targeting player <name> (distance=...)`.

### Build & déploiement

```bash
cd CheeseCompatModsFix && ./gradlew build
# → build/libs/cheesecompatmodsfix-*.jar contient la nouvelle BossSpawner.class
# On extrait la classe seule :
jar xf build/libs/cheesecompatmodsfix-*.jar com/ace/cobbleboss/BossSpawner.class
cp com/ace/cobbleboss/BossSpawner.class \
   patches/cobblewarden/classes/com/ace/cobbleboss/BossSpawner.class
```

Note : le JAR `cheesecompatmodsfix` lui-même n'utilise PAS cette classe — elle
est uniquement extraite pour être injectée dans `cobblewarden_boss-fabric-1.0.0.jar`.

## Patch 4 — Bypass total de `hasSpawned` (supersède Patch 2)

### Motivation

Patch 2 laissait le warden se spawner normalement quand la région était déjà
marquée. Mais on veut que **chaque** warden (commande `/summon` incluse)
déclenche un boss, peu importe l'état du cache régional. Patch 4 rend
complètement inerte le check `AncientCityTracker.hasSpawned(level, pos)`.

### Fix bytecode

Dans `WardenSpawnInterceptor.class`, méthode `lambda$register$1`, aux offsets
26-36 (juste après le log "Warden spawn detected…") :

```
Avant :  2B 2A B6 00 A0   (aload_1, aload_0, invokevirtual hasSpawned)
         B8 00 A9         (invokestatic wrapper)
         99 00 11         (ifeq +17 → branche "non spawné")

Après :  00 × 7           (7 × NOP)
         03               (iconst_0 — pousse 0 sur la pile)
         99 00 11         (ifeq +17 → 0 == 0, saute toujours à offset 51)
```

Total : 11 bytes préservés, même taille de méthode, même exception tables. La
branche "already spawned" (offsets 37-50) devient dead code inaccessible —
`EventResult.pass` y reste (héritage du Patch 2) mais n'est jamais atteint.

### Piège verifier — pourquoi pas un simple `goto`

Tentation naturelle : remplacer par `goto 51` (3 bytes : `A7 00 11`) précédé de
8 NOPs. **Ça fait crasher le serveur au chargement** avec :

```
java.lang.VerifyError: Expecting a stack map frame
  Location: WardenSpawnInterceptor.lambda$register$1 @37: getstatic
```

Raison : `goto` est une **branche inconditionnelle**. Le JVM impose un
`StackMapFrame` à l'instruction immédiatement après (offset 37), même si elle
est dead code, car c'est un "basic block entry" qui ne peut être atteint que
par branche. Le `StackMapTable` attribute du fichier n'en contient pas à 37
(il n'en avait pas besoin dans l'original puisque 37 était le fall-through
d'un `ifeq` — branche conditionnelle).

Solution : utiliser `iconst_0 + ifeq 51` qui a le **même effet runtime**
(0 == 0 → saute toujours) mais est une branche conditionnelle. Son
fall-through à 37 n'a pas besoin de frame — le verifier déduit l'état depuis
la frame précédente en retirant juste le `int` poppé par `ifeq`.

### Comportement final (cumul Patches 2+4)

| Situation | Original | Patch 2 seul | Patch 2+4 |
|-----------|----------|--------------|-----------|
| 1er warden / région | cancelled + boss | cancelled + boss | cancelled + boss |
| Warden suivant | cancelled, trou noir | passe (vanilla warden) | cancelled + boss |
| `/summon` après boss mort | cancelled, trou noir | passe (vanilla warden) | cancelled + boss |

### Regénérer le Patch 4 from scratch

```python
# S'applique au fichier déjà patché par Patch 2 (EventResult.pass au offset 47)
orig = open('WardenSpawnInterceptor.class','rb').read()
pat = bytes([0x2B,0x2A, 0xB6,0x00,0xA0, 0xB8,0x00,0xA9, 0x99,0x00,0x11])
rep = bytes([0x00,0x00, 0x00,0x00,0x00, 0x00,0x00,0x03, 0x99,0x00,0x11])
#          [7 × NOP                                   ] [iconst_0] [ifeq +17]
assert orig.count(pat) == 1
open('WardenSpawnInterceptor.class','wb').write(orig.replace(pat, rep))
```

Les indices constant pool `#160` (hasSpawned), `#169` (invokestatic wrapper
qui précédait le ifeq) sont spécifiques à ce build. Regénérer via `javap -v`
si le mod est mis à jour.

## À refaire si

- Mise à jour du mod `cobblewarden_boss` → vérifier que le bytecode a toujours la
  même structure (même si la classe a changé, le *comportement* peut aussi avoir
  été corrigé upstream). Si c'est toujours le même bug, regénérer le pattern.
- Mise à jour du serveur vers Cobblemon 1.7+ / FightOrFlight 0.10+ → Patch 1
  devient inutile, redéployer le JAR original (mais Patch 2 reste pertinent).

## Cible

Déployé sur :

- un serveur de test interne (21 avril 2026) — Patch 1 + Patch 2

## Artifacts

- `fabric.mod.json` — version patchée pour Patch 1
- `classes/com/ace/cobbleboss/WardenSpawnInterceptor.class` — version patchée pour Patch 2
