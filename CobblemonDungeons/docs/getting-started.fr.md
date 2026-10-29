[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de ce parcours tu auras un donjon fonctionnel sur un seul serveur : une petite
arène avec deux Pokémon Alpha et un dresseur Gardien, dans laquelle tu entres avec un
ticket que tu t'es donné, que tu termines en battant un boss Charizard, avec ton temps au
classement et la partie visible dans le dashboard. Compte une demi-heure, dont l'essentiel
passé à poser des blocs.

Le donjon que nous construisons tourne en mode `SAME_SERVER`. Les donjons cross-serveur
demandent un proxy et un second serveur, et c'est un exercice distinct —
[l'installation](installation.fr.md) couvre la mise en place.

Prérequis : un serveur Fabric 1.21.1 avec Cobblemon, RCTApi, CheeseDash et ce mod
installés et démarrés une fois, plus un client avec le mod. Si `/dungeons` n'existe pas
encore, retourne à [l'installation](installation.fr.md).

---

## 1. Confirmer que le mod tourne

Connecte-toi et lance :

```mcfunction
/dungeons
```

Tu devrais obtenir l'écran de sélection des donjons, listant **Generic Dungeon** — le
donjon d'exemple écrit au premier démarrage. Ferme-le.

Si tu vois à la place *Vous avez besoin d'un ou plusieurs pokémon dans votre équipe pour
entrer dans les donjons*, capture quelque chose d'abord. Chaque point d'entrée vérifie que
l'équipe Cobblemon n'est pas vide.

## 2. Créer ton propre donjon

Plutôt que de modifier l'exemple, crée un donjon neuf depuis le dashboard pour partir de
valeurs par défaut propres. Connecte-toi à CheeseDash, puis :

```bash
curl -X POST http://localhost:44311/api/v1/dungeons/configs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"tutorial","name":"Tutorial Dungeon"}'
```

La réponse est la configuration complète du nouveau donjon. Un donjon créé ainsi est forcé
en `SAME_SERVER`, ce qui est exactement ce que nous voulons. Tu peux tout aussi bien créer
`config/cobblemon_dungeons/dungeons/tutorial.json` à la main et recharger — le résultat est
le même fichier.

Relance `/dungeons` : **Tutorial Dungeon** est maintenant dans la liste. Il est injouable
pour l'instant, parce que toutes ses coordonnées valent `0, 100, 0` et qu'il n'a aucun
contenu.

## 3. Choisir tes coordonnées

Place-toi là où tu veux que les joueurs démarrent et note la position. Pour ce parcours il
te faut quatre endroits, qui peuvent être à portée de vue les uns des autres :

- **spawn** — où les joueurs atterrissent, et le centre de chaque scan de blocs
- **hub** — une petite salle fermée où les joueurs attendent pendant la préparation
- **salle du boss** — où le portail envoie le groupe
- **retour** — où les joueurs sont téléportés quand ils quittent

Note-les. `/tp` ou F3 te donnera les chiffres.

## 4. Remplir la configuration

Édite `config/cobblemon_dungeons/dungeons/tutorial.json`. Le minimum qui donne un donjon
jouable, avec les champs qui nous intéressent et les valeurs par défaut partout ailleurs :

```json
{
  "general": { "name": "Tutorial Dungeon", "id": "tutorial", "guiIcon": "minecraft:iron_sword" },
  "networking": { "teleportMode": "SAME_SERVER", "syncPlayerData": true },
  "locations": {
    "spawnPos":    { "x": 100, "y": 64, "z": 100, "dimension": "minecraft:overworld" },
    "hubPos":      { "x": 100, "y": 80, "z": 100, "dimension": "minecraft:overworld" },
    "bossRoomPos": { "x": 160, "y": 64, "z": 100, "dimension": "minecraft:overworld" },
    "returnPos":   { "x": 0,   "y": 70, "z": 0,   "dimension": "minecraft:overworld" }
  },
  "requirements": {
    "dungeonsPartySizeLimit": 5,
    "timeLimitSeconds": 900,
    "requiredAlphas": 2,
    "requiredGuardians": 1,
    "requiredGems": 0,
    "requiredStakes": 0,
    "soloLives": 3,
    "cooldownSeconds": 0
  },
  "scaling": { "tier": 1, "tierLevels": [ { "tier": 1, "level": 20 } ] },
  "spawning": {
    "alphaPool": [ { "species": "gastly", "tier": 0, "minLevel": 18, "maxLevel": 22, "weight": 10 } ],
    "alphaSpawnerIds": [ 1 ],
    "guardianSpawnerIds": [ 1 ],
    "portalIds": [ 1 ],
    "chestIds": [ 1 ],
    "alphaSpawnRadius": 64,
    "guardianSpawnRadius": 64,
    "portalSpawnRadius": 64,
    "chestSpawnRadius": 64,
    "guardianPool": [ { "trainerId": "example_trainer", "tier": 0, "weight": 10, "level": 20 } ]
  },
  "boss": {
    "bossSpawnPos": { "x": 160, "y": 64, "z": 100, "dimension": "minecraft:overworld" },
    "pool": [
      { "species": "charizard", "tier": 0, "level": 30, "customName": "Tutorial Boss",
        "isShiny": false, "weight": 10, "rewardChance": 100.0, "scale": 3.0 }
    ],
    "customName": "Tutorial Boss"
  },
  "rewardCap": 1,
  "lootPools": [
    { "id": 1, "tier": 0, "rolls": 2,
      "items": [ { "item": "cobblemon:ultra_ball", "min": 2, "max": 5, "weight": 20 } ] }
  ],
  "completionRewards": [
    { "item": "minecraft:diamond", "min": 1, "max": 3, "chance": 100.0, "tier": 0 }
  ]
}
```

Deux détails faciles à rater :

- `boss.customName` reçoit la même chaîne que le `customName` de l'entrée du pool.
  Garde-les alignés : le champ historique est encore consulté dans un chemin de code, comme
  l'explique [la page des dresseurs](trainers.fr.md#compter-une-victoire).
- `requiredAlphas: 2` et `requiredGuardians: 1` sont à la fois des objectifs **et** des
  cibles d'apparition. Deux Alphas apparaîtront parce que tu en exiges deux.

Recharge :

```bash
curl -X POST http://localhost:44311/api/v1/dungeons/configs/reload \
  -H "Authorization: Bearer $TOKEN"
```

```json
{"reloaded":true}
```

Le log affiche `Registered dungeon from file: tutorial.json`.

## 5. Construire l'arène

Pose maintenant les blocs que le donjon va trouver. Tout est positionnel : la configuration
ne stocke jamais une coordonnée de bloc, elle stocke un rayon de recherche et une liste de
numéros de variantes.

Donne-toi les blocs et pose-les à moins de 64 blocs de ta position de spawn :

```mcfunction
/give @s cobblemon_dungeons:alpha_spawner 2
/give @s cobblemon_dungeons:guardian_spawner 1
/give @s cobblemon_dungeons:portal 1
/give @s cobblemon_dungeons:dungeon_chest 2
```

- **Deux spawners d'Alpha**, espacés. Chacun se déclenche quand un joueur arrive à moins de
  `alphaTriggerRadius` (5 blocs par défaut).
- **Un spawner de Gardien**, quelque part où il faut marcher pour y aller.
- **Un portail**, près du spawn. Il reste invisible jusqu'à ce que tous les objectifs
  soient complétés.
- **Deux coffres de donjon**, n'importe où. Leur numéro de variante — `1` ici — est aussi
  l'`id` du pool de butin qui les remplit.

Construis une boîte fermée aux coordonnées de ton hub. Les joueurs y passent la phase de
préparation et ne doivent pas pouvoir s'échapper.

Si tu veux des gemmes comme objectif, mets un `requiredGems` positif dans la configuration
et le mod sèmera les gemmes dans les coffres pour toi — l'item Gemme se consomme lui-même
et compte dès qu'il atterrit dans l'inventaire d'un joueur en cours de donjon. Nous l'avons
laissé à zéro pour garder le parcours court.

## 6. Te donner un ticket

L'entrée coûte un ticket correspondant à l'identifiant du donjon et au tier, et le
`dungeon_tickets.json` par défaut n'a qu'un modèle pour `generic_dungeon`. Ajoute-en un pour
le donjon du tutoriel :

```bash
curl -X PUT http://localhost:44311/api/v1/dungeons/tickets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tickets":[
        {"dungeonId":"generic_dungeon","itemName":"§6Copper Dungeon Ticket","tier":1},
        {"dungeonId":"tutorial","itemName":"§bTutorial Ticket","tier":1}
      ]}'
```

Reconnecte-toi — le client ne reçoit la liste des tickets qu'à la connexion — puis donne-toi
l'item avec ses données personnalisées :

```mcfunction
/give @s cobblemon_dungeons:dungeon_ticket[custom_data={DungeonId:"tutorial",Tier:1},custom_name='{"text":"Tutorial Ticket","color":"aqua","italic":false}'] 1
```

Survole-le. L'infobulle doit afficher :

```text
Tutorial Ticket
Donjon : tutorial
Tier : 1
```

Si ces deux lignes manquent, le `custom_data` n'a pas pris et le ticket sera refusé au
guichet.

## 7. Entrer dans le donjon

```mcfunction
/dungeons
```

Choisis **Tutorial Dungeon**, puis **Tier 1**. Dans l'ordre, tu devrais voir :

```text
Le donjon démarre dans 5 secondes... Préparez votre équipe !
Démarrage dans 4...
Démarrage dans 3...
Donjon lancé : Tutorial Dungeon
Préparation de la zone du donjon... Veuillez patienter.
```

Ton ticket a disparu et tu es dans le hub. Le HUD apparaît au bord de l'écran avec les
compteurs d'objectifs, les vies restantes et le chronomètre.

Une seconde ou deux plus tard :

```text
Le donjon est prêt ! Préparez-vous.
```

et tu es téléporté à la position de spawn. Le Gardien est apparu entre-temps : la
préparation ne se termine pas avant, et c'est précisément la raison des deux téléportations.

Si tu es toujours dans le hub au bout de dix secondes, le Gardien n'a pas pu apparaître.
Regarde le log et consulte [le dépannage](troubleshooting.fr.md).

## 8. Remplir les objectifs

**Les Alphas.** Marche vers un spawner. Un grand Fantominus agressif apparaît avec une
barre de boss et te fonce dessus. Ne lance pas de Poké Ball et ne démarre pas de combat
Cobblemon — ni l'un ni l'autre ne fonctionne sur un Alpha, et le mod te le dira. Frappe-le
avec une arme. À sa mort :

```text
<toi> a vaincu un Alpha ! (1/2)
```

Fais de même avec le second.

**Le Gardien.** Clic droit sur le PNJ au spawner de Gardien. Un combat de dresseur Cobblemon
normal démarre contre l'équipe de `trainers/example_trainer.json` — un Pikachu, au niveau
indiqué par ton entrée de pool. Gagne-le :

```text
<toi> a vaincu a Guardian ! (1/1)
```

Ce « a Guardian » au milieu d'une phrase française n'est pas une erreur de traduction : le
libellé est construit en anglais dans le code Java et inséré dans la chaîne traduite. Le
fichier de langue n'y peut rien.

**Les coffres.** Pas un objectif, mais ouvres-en un : il génère deux tirages d'Hyper Balls,
une seule fois, et retient son contenu pour le reste de la partie.

Quand le dernier objectif tombe :

```text
Le portail a été activé ! Tous les objectifs sont complétés.
```

et le bloc de portail que tu as posé devient visible.

## 9. Battre le boss

Clic droit sur le portail. L'invite de combat de boss apparaît ; accepte. Tu es téléporté
dans la salle du boss et :

```text
Le Boss Tutorial Boss est apparu !
```

Un Charizard trois fois plus grand que la normale. Encore une fois : combat Minecraft, pas
combat Pokémon. Tue-le et la partie se termine :

```text
Félicitations ! Le boss du donjon a été vaincu !
Le groupe a gagné ! Vous serez téléportés dans 10 secondes.
Charizard a été transféré dans votre PC !
Vous avez reçu une récompense : Diamant
```

Le boss lui-même atterrit dans ton PC parce que `rewardChance` valait `100.0`, et tu reçois
une seule récompense de complétion parce que `rewardCap` valait `1`. Dix secondes plus tard
tu es de retour à ta position de retour.

## 10. Vérifier les enregistrements

La partie est désormais dans trois endroits.

```bash
curl -s http://localhost:44311/api/v1/dungeons/stats -H "Authorization: Bearer $TOKEN"
```

```json
{"totalRuns":1,"successCount":1,"successRate":100,"avgDurationSeconds":214,
 "configCount":2,"activeInstances":0,
 "runsByDungeon":{"tutorial":{"total":1,"success":1}},
 "runsByTier":{"1":{"total":1,"success":1}}}
```

```bash
curl -s http://localhost:44311/api/v1/dungeons/leaderboard/tutorial -H "Authorization: Bearer $TOKEN"
```

```json
{"tutorial":{"1":[{"playerName":"RoiCheese","playerUuid":"…","durationSeconds":214,"date":1761000000}]}}
```

Et sur le disque : `run_history.json`, `leaderboard.json` et `cooldowns.json` existent
maintenant dans `config/cobblemon_dungeons/`.

## Pour aller plus loin

- Ajoute un second emplacement dans `additionalLocations` pour que deux groupes puissent
  faire le donjon en même temps — voir
  [la définition d'un donjon](dungeons.fr.md#locations-et-additionallocations).
- Règle `cooldownSeconds` et une table de tiers, puis construis du contenu de Tier 2 à côté
  du Tier 1 en marquant pools de butin, entrées de boss et entrées de Gardien avec
  `tier: 2`.
- Écris tes propres Gardiens dans `config/cobblemon_dungeons/trainers/` — en te rappelant
  que les dresseurs ne se chargent qu'au démarrage du serveur, d'après
  [les dresseurs et RCTApi](trainers.fr.md).
- Distribue des tickets via un événement CheeseGuard avec `DUNGEON_TICKET_GIVE`, documenté
  dans [la page dashboard](dashboard.fr.md#effets-dévénement-cheeseguard).

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
