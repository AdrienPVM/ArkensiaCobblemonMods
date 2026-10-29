[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

```text
config/cheesecompatmodsfix.json
```

Trois booléens. C'est toute la surface de configuration d'un mod qui porte quarante
correctifs — un choix délibéré, et une limite qu'il faut dire : **presque rien ici n'est
commutable**. Si un correctif ne te plaît pas sur ton serveur, il n'y a pas de drapeau pour
lui ; le seul levier est de retirer le jar.

Le chemin est résolu en `Path.of("config", "cheesecompatmodsfix.json")`, relativement au
répertoire de travail du serveur. Les trois autres fichiers que ce mod écrit passent par
`FabricLoader.getConfigDir()`. Au démarrage normal, c'est le même dossier ; avec un launcher
qui pose un autre répertoire de travail, non, et ce fichier-là sera le seul à atterrir
ailleurs.

---

## Clés

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `arcPhoneHealEnabled` | booléen | `true` | à `false`, l'Arc Phone de Legendary Monuments ne peut plus soigner une équipe |
| `arcPhonePCEnabled` | booléen | `true` | à `false`, l'Arc Phone ne peut plus ouvrir le PC Pokémon |
| `sanctuaryOverflowFix` | booléen | `true` | lue au démarrage et journalisée ; voir l'avertissement plus bas |

### `arcPhoneHealEnabled`

À mettre à `false` sur un serveur qui veut que les Centres Pokémon comptent. Le soin de
l'Arc Phone est gratuit, instantané et disponible partout : le laisser actif retire
l'essentiel des raisons de retourner en ville.

Appliquée par `ArcPhoneHealMixin`, qui annule le gestionnaire de paquet côté serveur et
envoie au joueur `cheesecompat.arcphone.heal_disabled` :

```text
Le soin via l'Arc Phone est désactivé sur ce serveur.
```

Seul le gestionnaire côté serveur est bloqué. Le bouton de soin de l'Arc Phone existe
toujours côté client et peut toujours être pressé ; le joueur reçoit le message au lieu d'un
soin.

### `arcPhonePCEnabled`

Même forme, pour l'accès au PC. Appliquée par `ArcPhonePCMixin`, message
`cheesecompat.arcphone.pc_disabled` :

```text
L'accès au PC via l'Arc Phone est désactivé sur ce serveur.
```

### `sanctuaryOverflowFix`

⚠️ **Cette clé ne désactive rien.** Elle est chargée, et sa valeur est affichée dans la
ligne de log de démarrage, mais `SanctuaryDistanceMixin` ne la lit pas — le correctif du
débordement d'entier dans `SanctuaryBlock.isWithinRadius()` est appliqué
inconditionnellement dès que Legendary Monuments est installé.

La passer à `false` change le mot d'une ligne de log de `ON` à `OFF` et rien d'autre. Elle
est documentée ici comme clé vestigiale plutôt qu'omise discrètement, parce qu'une clé qui
ressemble à un interrupteur sans en être un est pire que pas de clé du tout. Considère le
correctif comme toujours actif.

---

## Rechargement

**Aucun.** `CheeseCompatConfig.load()` tourne une fois, comme première instruction de
`onInitialize()`, et le résultat est gardé dans un champ statique pour la durée du
processus. Il n'y a pas de commande de rechargement et pas de surveillance de fichier.
Modifier le fichier sur un serveur en marche n'a aucun effet, et le prochain redémarrage
chargera ta modification normalement.

## Ce qui se passe au premier démarrage

Le fichier n'existe pas, donc `load()` construit une instance par défaut et appelle `save()`
dessus. Tu obtiens :

```json
{
  "arcPhoneHealEnabled": true,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

écrit avec l'indentation Gson.

## Ce qui se passe quand une version ajoute une clé

`load()` réécrit la config **immédiatement après un parsing réussi**. Gson laisse à sa valeur
Java par défaut un champ absent du JSON, et la réécriture ressort ensuite tous les champs.
Donc une clé ajoutée dans une nouvelle version apparaît dans ton fichier existant au premier
démarrage avec cette version, déjà à son défaut. Tu n'as pas besoin de supprimer le fichier
pour récupérer les nouvelles clés.

Le même mécanisme réécrit le fichier à chaque démarrage, ce qui veut dire que **les
commentaires et la mise en forme ajoutés à la main sont perdus**. Le parseur `JsonObject` de
Gson refuse d'ailleurs les commentaires JSON, donc il n'y a nulle part où mettre une note.

## Ce qui se passe quand le fichier est mal formé

Un échec de parsing est attrapé, journalisé, et le mod retombe sur les défauts :

```text
[CheeseCompat] Failed to load config
```

suivi de l'exception. Le fichier cassé est ensuite **écrasé** par les défauts, parce que le
chemin de repli appelle `save()`. Il n'y a pas de sauvegarde. Si tu édites ce fichier à la
main et que tu rates le JSON, tes valeurs précédentes sont perdues au prochain démarrage —
ce qui, avec trois booléens, est supportable, mais autant le savoir.

Un fichier contenant un `null` littéral se parse en objet null et prend le même chemin.

## Exemple annoté

Un serveur qui veut que les Centres Pokémon soient le seul moyen de soigner, et le PC le
seul moyen d'atteindre les boîtes :

```json
{
  "arcPhoneHealEnabled": false,
  "arcPhonePCEnabled": false,
  "sanctuaryOverflowFix": true
}
```

Relecture, ligne par ligne :

- `arcPhoneHealEnabled: false` — le paquet de soin de l'Arc Phone est refusé côté serveur.
  Les joueurs reçoivent un message rouge. Les Centres Pokémon et les soigneurs ne sont pas
  affectés.
- `arcPhonePCEnabled: false` — le paquet PC de l'Arc Phone est refusé de la même façon. Les
  blocs PC physiques ne sont pas affectés.
- `sanctuaryOverflowFix: true` — aucun effet dans un sens ou dans l'autre, comme décrit
  plus haut. Laissée au défaut pour que la ligne de log affiche `ON` et corresponde à ce que
  le code fait réellement.

Les deux clés Arc Phone sont sans effet sur un serveur sans Legendary Monuments : les mixins
ne sont jamais appliqués, donc les paquets qu'ils gardent n'existent pas.

---

## Tout ce qui se comporte comme de la configuration

Plusieurs correctifs ont des nombres qu'un administrateur attendrait raisonnablement comme
configurables et qui sont **codés en dur**. Ils sont listés ici pour que tu saches où
regarder, et dans quel fichier les modifier si tu es prêt à reconstruire.

| Constante | Valeur | Où | Ce qu'elle contrôle |
|---|---|---|---|
| offset PNJ de l'arène | `18` | `BattleArenaPositionMixin` | distance de l'ancre à la plateforme du PNJ — [géométrie de l'arène](fixes-battle-tower.fr.md#8-offsets-de-téléportation-de-larène) |
| `WAIT_INTERVAL_TICKS` / `MAX_ATTEMPTS` | `20` / `60` | `BattleTowerDisconnectFix` | cadence de réessai de restauration d'équipe et point d'abandon |
| `BACKUP_MAX_AGE_MS` | 30 jours | `BattleTowerDisconnectFix` | âge auquel une sauvegarde d'équipe inutilisée est balayée |
| échelle de délais de combat | 1500–3000 ms | `BattleTowerManagerMixin` | délai par étage entre deux combats de tour |
| `GIVE_UP_THRESHOLD` | `1200` ticks | `RaidDenFix` | moment où le mod cesse d'essayer de fermer un cristal |
| `COOLDOWN_MILLIS` / `COOLDOWN_RADIUS` | 10 min / 200 blocs | `ShriekerBossState` | délai d'apparition d'un boss de cri |
| `RADIUS` | `50` | `BossDarknessAura` | portée de l'aura de ténèbres du boss |
| portée du Repel | `80` blocs | `SafariSpawnInfluenceFix` | rayon de l'effet Repel du safari |
| distance de cull des boutiques | ~`24` blocs | `client.NumismaticShopCullMixin` | distance à laquelle une boutique Numismatic cesse d'afficher son item |
| `FAKEMON_NAMESPACES` | `seavlbizarremon` | `client.JourneyMapSeavlianIconMixin` | quels espaces de noms voient leur chemin d'icône de minimap réécrit |
| `REMAP` | 42 entrées | `CacademyMegaStoneRemapPatch` | table méga-gemme custom → id de gemme Showdown |

## Autres fichiers que ce mod écrit

Aucun de ceux-ci n'est un fichier de configuration — ne les édite pas à la main — mais ils
vivent dans `config/` et apparaîtront à côté du précédent.

| Chemin | Propriétaire | Contenu |
|---|---|---|
| `config/cheesecompat_bt_backups/<uuid>.dat` | `BattleTowerDisconnectFix` | snapshot NBT compressé de l'équipe Battle Tower d'un joueur |
| `config/cheesecompat_daycare.json` | `DaycareSimpleModeStore` | état de reproduction ponctuelle par pâturage |
| `config/cheesecompatmodsfix_shrieker_cooldowns.json` | `ShriekerBossState` | délais des boss de cri de sculk, par position |

Les fichiers `.dat` sont la seule donnée ici que tu ne peux pas régénérer : chacun est
l'équipe d'un joueur. En supprimer un alors qu'une restauration est encore en cours pour ce
joueur perd tout ce que la tour n'avait pas encore restauré.

Un quatrième fichier est **lu** et jamais écrit : `config/resourcepackoverrides.json`, que
`CheeseCompatClient` consulterait pour sa liste de packs — sauf que cette classe n'est jamais
instanciée, donc rien ne le lit non plus. Voir
[le catalogue « tout le reste »](fixes-misc.fr.md#inatteignable--lactivateur-de-packs-de-ressources-client).

---

← [Commandes](commands.fr.md) · [Catalogue — Battle Tower](fixes-battle-tower.fr.md) →
