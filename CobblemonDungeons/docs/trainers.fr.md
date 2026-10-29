[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](trainers.md)

# Les dresseurs et RCTApi

Les Gardiens sont des dresseurs PNJ avec de vraies équipes Cobblemon. Le mod n'implémente
pas le combat lui-même : il délègue à **RCTApi**, la partie API de Radical Cobblemon
Trainers, qui est une dépendance dure déclarée dans `fabric.mod.json` sous la forme
`"rctapi": ">=0.13.7-beta"`.

Ce que ce mod possède, c'est : le chargement des définitions de dresseurs depuis des JSON,
leur enregistrement auprès de RCTApi dans son propre namespace, leur placement dans le monde
aux blocs spawner de Gardien, et le décompte d'une victoire comme objectif de donjon.

---

## Où se définissent les dresseurs

`config/cobblemon_dungeons/trainers/`, un fichier JSON par dresseur. Le nom du fichier sans
`.json` est l'identifiant du dresseur — la chaîne que tu mets dans
`guardianPool[].trainerId` ou dans `guardianTrainerIds[]`.

Le dossier est créé au premier démarrage, avec `example_trainer.json` :

```json
{
  "name": "Example Guardian",
  "entity": "cobblemon:npc",
  "skin": "Steve",
  "team": [
    {
      "species": "pikachu",
      "level": 20
    }
  ]
}
```

Le schéma est le `TrainerModel` de RCTApi — `name`, `team`, règles de combat et tout ce que
RCT sait faire — étendu par `EnhancedTrainerModel` avec quatre champs ajoutés par ce mod :

| Clé | Type | Effet |
|---|---|---|
| `entity` | string | Identifiant du type d'entité à utiliser comme corps du dresseur, par exemple `cobblemon:npc` |
| `skin` | string | Un nom de compte Minecraft dont le PNJ porte la peau |
| `texture` | string | Une URL de texture de peau, utilisée quand `skin` est absent |
| `modelType` | string | `SLIM` ou `ALEX` sélectionne le modèle de joueur fin ; toute autre valeur donne le modèle par défaut |

Comme le reste du fichier est analysé par l'instance Gson de RCTApi, la référence faisant
foi pour les entrées de `team` et les règles de combat est la documentation de RCT, pas
cette page. Ce qui est documenté ici est seulement ce que le mod lit lui-même.

## Comment se passe le chargement

`TrainerLoader.load` s'exécute sur `SERVER_STARTED` :

1. le dossier est créé s'il manque, et le fichier d'exemple écrit s'il est absent
2. `RCTApi.initInstance("cobblemon_dungeons")` donne au mod sa propre instance RCT, de sorte
   que ses dresseurs vivent dans un registre séparé de celui de `radicaltrainers`
3. chaque `*.json` est désérialisé en `EnhancedTrainerModel` puis converti en `TrainerNPC`
4. si `entity` est défini, l'entité est créée, rendue invulnérable, marquée du tag
   `DungeonTrainer`, et reçoit le nom du modèle comme nom personnalisé visible
5. pour une `NPCEntity` Cobblemon, la classe de PNJ est forcée à `standard` — sans cela
   Cobblemon retombe sur sa classe « kitchen sink » — et la peau ou la texture est
   appliquée, la source étant consignée en tag de commande (`DungeonSkin:…`,
   `DungeonTexture:…`, `DungeonModel:…`)
6. le dresseur est enregistré sous l'identifiant tiré du nom de fichier, et journalisé
   `Registered custom trainer from file: <fichier>.json`

Un fichier qui échoue à la conversion journalise
`Failed to convert trainer model from file: …` et est ignoré ; les autres se chargent quand
même. Un fichier qui échoue à l'analyse journalise
`Failed to load custom trainer file: …`.

⚠️ Les dresseurs se chargent **une seule fois**, au démarrage du serveur. Le dashboard sait
lire et écrire les fichiers de dresseurs, et les configurations de donjons se rechargent à
chaud, mais un fichier de dresseur modifié ne prend pas effet avant un redémarrage. Rien
dans le code ne relance `TrainerLoader.load`.

## Le placement dans le monde

Les Gardiens ne sont pas placés par coordonnées. Tu construis le donjon et tu poses des
**blocs spawner de Gardien** là où tu les veux — `guardian_spawner`, `guardian_spawner_2`
jusqu'à `guardian_spawner_10` — puis tu listes les numéros de variantes que ce donjon doit
utiliser dans `spawning.guardianSpawnerIds`.

Au démarrage d'une partie, l'instance cherche ces blocs dans le registre par monde dans un
rayon de `spawning.guardianSpawnRadius` autour de `spawnPos` (avec
`spawnerScanVerticalRange` comme demi-portée verticale), puis fait apparaître jusqu'à
`requirements.requiredGuardians` dresseurs aux positions trouvées, en file d'attente pour
étaler le travail sur plusieurs ticks.

Le dresseur qui apparaît à chaque position est un tirage pondéré sur
`spawning.guardianPool`, filtré comme toute autre liste tiérée : les entrées dont le `tier`
égale celui de la partie, avec repli sur les entrées de `tier: 0`. Le `level` d'une entrée
de pool, quand il est supérieur à zéro, remplace le niveau que la table de tiers aurait
donné.

`spawning.guardianTrainerIds` est l'échappatoire : les identifiants qui y figurent
apparaissent inconditionnellement, hors du tirage pondéré. L'exemple généré s'en sert pour
un dresseur nommé proche du boss (`"Guardian Leader"`).

La préparation ne se termine pas avant que chaque Gardien en file ne soit apparu —
`isReadyForPlayers()` l'exige — de sorte que le groupe attend dans le hub au lieu de voir
les dresseurs surgir autour de lui.

## Combattre un Gardien

Le clic droit sur un dresseur passe par `DungeonManager.handleTrainerInteract` :

1. si le joueur est déjà en combat Cobblemon : *Vous êtes déjà en combat !*
2. l'entité est résolue en identifiant de dresseur, d'abord dans le registre RCT de ce mod,
   puis dans le registre `radicaltrainers` en repli
3. si toute l'équipe du dresseur est à zéro PV : *Ce dresseur a déjà été vaincu.*
4. sinon le PNJ se tourne vers le joueur et `api.getBattleManager().startSingle` est appelé

Le repli sur le registre `radicaltrainers` signifie qu'un dresseur posé par Radical Trainers
lui-même, et non par ce mod, reste combattable via une interaction de donjon. Savoir s'il
*compte* est une autre question — voir ci-dessous.

## Compter une victoire

`BattleHandler` s'abonne à l'événement `BATTLE_VICTORY` de Cobblemon. Pour chaque perdant,
il décide s'il s'agissait d'un Gardien, selon le premier de ces critères qui correspond :

1. l'entité vaincue porte le tag de commande `DungeonGuardian`
2. le nom du PNJ contient le mot `guardian`, sans tenir compte de la casse
3. le nom du PNJ figure dans la configuration de donjon du joueur vainqueur, soit dans
   `spawning.guardianTrainerIds`, soit comme `guardianPool[].trainerId`
4. le nom du PNJ se résout en un dresseur du registre RCT de ce mod

Une correspondance incrémente le compteur de Gardiens, supprime l'entité et diffuse
*<joueur> a vaincu a Guardian ! (N/M)* — le libellé anglais au milieu de la phrase française
est construit côté Java et inséré dans la chaîne traduite.

Le critère 2 est une heuristique et mérite d'être connu : n'importe quel dresseur PNJ dont
le nom contient « guardian » compte comme Gardien de donjon quand un joueur en partie le
bat, même s'il n'a rien à voir avec le donjon. Si tu fais tourner du contenu Radical
Trainers à côté des donjons, évite ce mot dans les noms de dresseurs sans rapport.

Le même gestionnaire compte aussi les Alphas — les perdants sauvages portant l'aspect
`alpha` — et contient une vérification de boss qui compare le surnom d'un Pokémon sauvage au
`boss.customName` historique. Cette branche ne peut pas se déclencher en pratique : le boss
apparaît avec l'aspect `alpha`, et `BattleHandler` annule tout combat contenant un Alpha,
donc un boss n'est jamais un perdant de combat. Le chemin qui termine réellement une partie
est dans `DeathHandler`, qui se base sur le tag de commande `DungeonBoss` porté par l'entité
du boss, et qui est fiable quels que soient les noms.

## Les Alphas ne sont pas des dresseurs

Les Pokémon Alpha sont des entités sauvages portant l'aspect `alpha`, agrandies et dotées
de statistiques de combat personnalisées, et ils sont volontairement **non** combattables
via Cobblemon. `BattleHandler` annule tout combat qui les inclurait et annonce au joueur
*Vous ne pouvez combattre les Alphas qu'en PVP Minecraft !* — ils se combattent avec une
arme, pas avec une équipe. `CaptureHandler` bloque de même la capture : *Les Pokémon Alpha
ne peuvent pas être capturés !*

---

← [Les tickets](tickets.fr.md) · [Classements et historique des parties](records.fr.md) →
