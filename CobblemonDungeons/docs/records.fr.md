[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](records.md)

# Classements et historique des parties

Trois fichiers JSON dans `config/cobblemon_dungeons/` contiennent tout ce que le mod retient
d'un redémarrage à l'autre. Les trois sont chargés par `ConfigLoader.load()` — ce qui veut
dire qu'un rechargement de configuration depuis le dashboard les recharge aussi — et chacun
est réécrit intégralement dès qu'il change.

| Fichier | Contenu | Écrit quand |
|---|---|---|
| `run_history.json` | Les 1000 dernières parties, réussies ou non | À chaque fin de partie |
| `leaderboard.json` | Les 10 meilleurs temps par donjon et par tier | À chaque partie réussie |
| `cooldowns.json` | Dernière complétion par donjon et par joueur | À chaque partie réussie |

Aucun des trois n'utilise la base de données SQL. La base ne sert jamais qu'aux données
joueur cross-serveur, jamais aux enregistrements — l'affirmation de l'ancien README selon
laquelle l'historique et les classements peuvent vivre dans MariaDB ne correspond pas au
code.

---

## L'historique des parties

`DungeonManager.logRun` est appelé depuis exactement deux endroits : `handleBossDefeated`
avec `success = true`, et `failDungeon` avec `success = false`. Une partie qui se termine
parce que le dernier joueur s'est déconnecté n'est jamais enregistrée.

Chaque entrée est un record `DungeonRunLog` :

| Champ | Type | Signification |
|---|---|---|
| `dungeonId` | string | `general.id` du donjon |
| `dungeonName` | string | `general.name` au moment de la partie |
| `tier` | int | Tier auquel la partie a été lancée |
| `players` | tableau de strings | **Noms** de joueurs, ou les 8 premiers caractères de l'UUID pour qui est hors ligne |
| `startTime` | long | Secondes Unix, depuis la création de l'instance |
| `endTime` | long | Secondes Unix, à l'enregistrement |
| `success` | boolean | Le boss a-t-il été vaincu |
| `bossSpecies` | string | Espèce du boss apparu, vide si aucun |
| `deathCount` | int | Vies consommées pendant la partie |
| `durationSeconds` | long | `endTime - startTime`, calculé à la sérialisation |

Des noms, pas des UUID. Un joueur qui change de pseudo apparaît sous les deux noms dans
l'historique, et il n'y a aucun moyen d'interroger l'historique par compte.

`deathCount` est dérivé, pas compté : c'est le total de vies de départ du groupe moins les
vies restantes. Le total de départ est recalculé au moment de l'enregistrement à partir de
`instance.getPlayers().size()`, qui a déjà diminué si quelqu'un est parti ou s'est
déconnecté en cours de route. Pour un groupe ayant perdu des membres, `deathCount` est donc
faux — généralement trop bas, et il peut être ramené à zéro.

Les nouvelles entrées vont en **tête** de liste : l'index 0 est la partie la plus récente.
Au-delà de 1000 entrées, la plus ancienne est supprimée. Toute la liste est resérialisée sur
le disque à chaque écriture, soit quelques centaines de kilooctets par partie à 1000
entrées — acceptable à un rythme humain, pas quelque chose à déclencher en boucle.

Servi au dashboard par `GET /api/v1/dungeons/stats/history?n=50`.

## Les statistiques

Il n'existe aucun objet de statistiques stocké. `GET /api/v1/dungeons/stats` parcourt
l'historique en mémoire à chaque requête et calcule :

- `totalRuns`, `successCount`, `successRate` (pourcentage arrondi)
- `avgDurationSeconds` — la moyenne des `endTime - startTime` sur les parties de durée
  positive, divisée par le nombre **total** de parties
- `configCount` et `activeInstances`, lus en direct
- `runsByDungeon` et `runsByTier`, chacun sous la forme `{total, success}`

La moyenne est légèrement fausse par construction : le numérateur ignore les parties de
durée non positive alors que le dénominateur les compte. Avec un historique propre l'écart
est nul ; avec des horodatages corrompus il tire la moyenne vers le bas.

Tout est plafonné par les 1000 entrées de l'historique. Il n'y a aucun compteur
de tous les temps.

## Le classement

`updateLeaderboard` ne tourne qu'en cas de succès, depuis `handleBossDefeated`. La structure
est `dungeonId → tier → liste d'entrées`, chaque liste triée par durée croissante et
tronquée à dix.

| Champ | Type | Signification |
|---|---|---|
| `playerName` | string | Nom au moment de l'enregistrement, ou le préfixe d'UUID si hors ligne |
| `playerUuid` | string | L'UUID du joueur |
| `durationSeconds` | long | Durée de la partie en secondes |
| `date` | long | Secondes Unix au moment de l'enregistrement |

Chaque membre du groupe obtient sa propre entrée avec la **même** durée : celle de la
partie, pas une durée personnelle. Un groupe de quatre qui finit en 300 secondes place
quatre entrées de 300 secondes dans le même top dix.

Une entrée par joueur, par donjon et par tier : une entrée existante n'est remplacée que si
le nouveau temps est strictement meilleur, et laissée intacte sinon. Ne garder que dix
entrées par tier signifie qu'un donjon avec un groupe régulier important peut avoir son
tableau entièrement occupé par la partie d'un seul groupe.

La durée est ici calculée comme `now - (startTime / 1000)` en secondes, indépendamment du
`durationSeconds` qu'enregistre l'entrée d'historique. Les deux concordent, mais ce sont
deux calculs distincts sur les mêmes horloges.

Servi par `GET /api/v1/dungeons/leaderboard` pour tout, ou
`GET /api/v1/dungeons/leaderboard/<dungeonId>` pour un seul donjon.

## Les cooldowns

`cooldowns.json` est `dungeonId → { playerUuid → dernièreComplétionEnSecondesUnix }`. Une
entrée est écrite pour chaque membre du groupe en cas de succès, et rien n'est écrit en cas
d'échec.

Le temps restant est calculé à la demande depuis `requirements.cooldownSeconds` : baisser
cette valeur dans la configuration raccourcit immédiatement tous les cooldowns en cours, et
la mettre à `0` désactive la vérification sans vider le fichier. L'augmenter les prolonge
rétroactivement.

Les entrées ne sont jamais purgées. Le fichier grandit d'un UUID par joueur et par donjon et
garde cette taille indéfiniment ; à quelques dizaines d'octets par entrée ce n'est pas un
problème pratique, mais ce n'est pas nettoyé non plus.

La table des cooldowns est exposée en interne par `getAllCooldowns()`, qu'aucune route du
dashboard n'appelle aujourd'hui — il n'existe pas d'endpoint REST pour les cooldowns. Pour
en effacer un : arrête le serveur, édite `cooldowns.json`, redémarre.

## Ce qu'un rechargement fait à tout cela

`POST /api/v1/dungeons/configs/reload` appelle `ConfigLoader.load()`, qui vide le registre de
donjons, relit chaque fichier de configuration, puis relit l'historique, les cooldowns et le
classement depuis le disque. Comme ces trois fichiers sont aussi écrits sur le disque à
mesure qu'ils changent, l'aller-retour est normalement sans effet. Si tu as édité l'un des
fichiers à la main pendant que le serveur tournait, c'est un rechargement qui prend ton
changement en compte — et une partie qui se termine avant que tu ne recharges écrasera ton
édition.

---

← [Les dresseurs et RCTApi](trainers.fr.md) · [Intégration dashboard](dashboard.fr.md) →
