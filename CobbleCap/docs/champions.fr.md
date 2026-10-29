[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](champions.md)

# Le système de champion

Les champions sont la seule chose qui fait bouger la limite d'un joueur d'elle-même. Un
champion est un PNJ de dresseur rctmod pour lequel CobbleCap possède une entrée, et le
battre relève la limite et débloque l'entrée suivante de la liste.

⚠️ C'est dans ce sous-système que CobbleCap cesse d'être générique. Il attend **Radical
Cobblemon Trainers** (`rctmod`), éventuellement **FTB Quests**, et — à quatre endroits — un
champion dont l'identifiant de dresseur est littéralement `champion_regis_challenge`. Ces
quatre codages en dur sont listés au bas de cette page, sous
[ce que tu dois changer](#ce-que-tu-dois-changer-pour-ton-propre-serveur).

## Le modèle

```text
champions.json  ──liste ordonnée──▶  ChampionEntry
                                          │  id = identifiant de dresseur rctmod
                                          │  capGranted
                                          └─ conditions : requiredPokedex, requiredQuestId,
                                                          requiredScoreboard + requiredScoreMin
players.json    ──par joueur──────▶  PlayerCapData
                                          │  championsBeaten   (victoires normales)
                                          │  challengeBeaten   (victoires en défi)
                                          └─ lastChallengeTimes (cooldowns)
```

`ChampionConfig.nextUnbeaten(championsBeaten)` renvoie la première entrée absente de la
liste des champions battus. Ce seul appel définit « le champion actuel du joueur », et il
sert au HUD, à `/cobblecap`, et à la condition d'ordre.

Chaque champ de `ChampionEntry`, avec sa valeur par défaut en cas d'absence du JSON :

| Champ | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | `""` | l'identifiant de dresseur rctmod auquel cette entrée correspond |
| `displayName` | chaîne | `""` | montré aux joueurs ; **sert aussi à dériver la clé d'advancement** |
| `capGranted` | entier | `20` | la limite posée à la première victoire, en montée seule |
| `requiredPokedex` | entier | `0` | Pokémon capturés exigés avant le combat ; `0` désactive |
| `requiredQuestId` | chaîne (hex) | `""` | quête FTB à terminer avant le combat |
| `victoryQuestId` | chaîne (hex) | `""` | quête FTB associée à la victoire |
| `challengeId` | chaîne | `""` | l'identifiant de dresseur de la variante défi |
| `challengeQuestId` | chaîne (hex) | `""` | quête FTB pour la victoire en défi |
| `challengeCooldownHours` | entier | `0` | heures entre deux tentatives de défi ; `0` désactive |
| `requiredScoreboard` | chaîne | `""` | objectif vanilla conditionnant le combat |
| `requiredScoreMin` | entier | `0` | score minimum sur cet objectif |
| `challengeRequiredScoreMin` | entier | `0` | score minimum pour le combat de défi ; `0` désactive |
| `allowRefight` | booléen | `true` | censé permettre de réaffronter un champion battu ; en pratique **sans effet** (voir la condition ci-dessous) |
| `challengeRequiresAllBeaten` | booléen | `true` | le défi exige que tous les champions normaux soient battus |
| `challengeCapGranted` | entier | `0` | limite accordée à la victoire en défi ; `0` signifie aucune |

C'est `hasAllNormalChampionsBeaten` qui décide de ce que signifie « tous les champions
normaux », et la règle est assez singulière pour être explicitée : une entrée avec
`capGranted >= 100` **et** sans `challengeId` est considérée comme post-game et ignorée.
C'est ainsi que les membres du Conseil 4 et le champion final cohabitent dans la même liste
sans bloquer le déblocage des défis.

## Détection de victoire

`CobblemonHook` s'abonne à `BATTLE_VICTORY` (ou `BATTLE_WON`) et
`ChampionManager.handleBattleVictoryEvent` fait l'identification, entièrement par
réflexion :

1. Lire `getWinners()`, parcourir les acteurs, prendre le premier qui livre un UUID de
   joueur. `getPlayerUUIDs()`/`getUUIDs()` d'abord, puis `getUuid()`/`uuid`/`getPlayerId`.
2. **Aucun joueur gagnant** signifie qu'un PNJ a gagné — cela part vers le chemin de défaite
   compétitive décrit plus bas, et rien d'autre ne se produit.
3. Lire `getLosers()` et prendre le premier acteur porteur d'une `Entity`. En cas d'échec,
   se replier sur `getBattle().getActors()` et prendre le premier acteur qui n'est pas le
   gagnant.
4. Extraire l'identifiant de dresseur de l'entité perdante et retirer tout suffixe de
   dimension.
5. Comparer d'abord avec `findByChallengeId`, puis avec `findById`. Aucune correspondance
   signifie que ce n'était pas un combat de champion et le gestionnaire retourne.

Les identifiants de dresseur sont lus dans le NBT de l'entité par
`extractRctmodTrainerId`, qui essaie les clés `TrainerData`, `trainer`, `trainerId`,
`configId`, `id`, `Trainer` — en tant que compound avec les sous-clés
`id`/`configId`/`trainerId`/`trainer`, ou en tant que chaîne simple. Si toutes les clés
échouent, il se replie sur un parcours de toutes les méthodes sans argument renvoyant une
`String` et retient le premier résultat contenant un tiret bas et long de plus de trois
caractères. Cette heuristique est volontairement large et elle peut capter quelque chose
sans rapport : une détection mystérieuse de champion sur un dresseur ordinaire a cette
cause.

### Le suffixe de dimension

`stripPerDimSuffix` retire un `_<8 caractères hex minuscules>` final — mais **seulement**
quand le préfixe restant figure dans `Conseil4TrainerCloner.CONSEIL4_BASE_IDS`. Il existe
parce que les dimensions Conseil 4 par joueur de CheeseGuard clonaient autrefois chaque
dresseur en une variante propre à la dimension. Ce cloneur est **désactivé** (voir
[architecture](architecture.fr.md#code-désactivé-et-dormant)) ; le retrait du suffixe
subsiste pour que les identifiants clonés laissés par le déploiement raté se résolvent
encore.

## La barrière d'avant-combat

Deux points d'entrée, et ils se recouvrent :

- **`UseEntityCallback`** dans `CapListener`, sur un clic droit sur toute entité du namespace
  `rctmod`. C'est ce qui produit le refus visible par le joueur.
- **`BATTLE_STARTED_PRE`** dans `CobblemonHook`, comme filet de sécurité pour les combats
  qui démarrent autrement.

`ChampionManager.handlePreBattle` est le contrôle de conditions :

1. Déjà dans `championsBeaten` ? Avec `allowRefight` à faux, c'est `FAIL` avec
   `cobblecap.msg.already_beaten` ; à vrai, c'est `PASS`. Cette branche est inatteignable :
   les deux points d'entrée ci-dessus n'appellent `handlePreBattle` que pour un champion **pas
   encore** battu, donc `allowRefight` ne décide jamais rien.
2. `requiredPokedex` non atteint → une ligne non remplie, `Pokédex : %d/%d capturés`.
3. `requiredQuestId` non terminée → `Complétez une quête requise d'abord`.
4. Score de `requiredScoreboard` inférieur à `requiredScoreMin` →
   `Tu dois d'abord parler à Alfred`. Ce texte est écrit pour le PNJ de quête d'Arkensia ;
   change-le dans les fichiers de langue si ta condition de scoreboard signifie autre chose.

Toute condition non remplie produit une ligne d'en-tête et la liste à puces :

```text
Vous ne pouvez pas défier Erika — conditions non remplies :
  - Pokédex : 61/80 capturés
```

Les messages sont limités à un par joueur toutes les **2 secondes**
(`shouldSendMessage`), parce que l'événement se déclenche deux fois par interaction.

`handlePreBattle` **échoue en mode ouvert** quand le joueur n'a pas d'entrée stockée, et
`isFtbQuestCompleted` échoue en mode ouvert sur toute erreur, y compris l'absence de FTB
Quests — un mod de quête manquant ne bloque jamais un combat. `getPokedexCount`, à
l'inverse, échoue en mode **fermé**, en renvoyant `0` : si la réflexion sur le Pokédex de
Cobblemon casse, toute condition de Pokédex devient infranchissable. Cette asymétrie est
délibérée et documentée dans le source.

`BATTLE_STARTED_PRE` impose en plus l'ordre : si le champion présent dans le combat n'est
pas `nextUnbeaten`, le combat est annulé avec `cobblecap.msg.champion_not_next`. Quand il
annule alors que la limite du joueur est déjà égale ou supérieure au `capGranted` de ce
champion, il journalise un avertissement qui nomme la commande de réparation, parce que
cette combinaison signifie presque toujours que `championsBeaten` et `capLevel` ont
divergé.

## Le mode défi

Un champion avec un `challengeId` non vide possède une variante plus dure. Interagir avec un
champion déjà battu dont le défi est débloqué laisse l'interaction passer (`PASS`) vers
rctmod ; `BATTLE_STARTED_PRE` opère ensuite le **basculement** :

1. `handleChallengePreBattle` vérifie les conditions — tous les champions normaux battus
   (sauf si `challengeRequiresAllBeaten` est faux), le minimum de scoreboard du défi, et le
   cooldown. Le refus de cooldown annonce les minutes restantes, arrondies au supérieur.
2. Le combat normal est annulé.
3. `swapToChallengeBattle` réfléchit sur
   `com.gitlab.srcmc.rctmod.world.entities.TrainerMob`, lit `getTrainerId()`, appelle
   `setTrainerId(challengeId)`, puis `startBattleWith(player)`.
4. L'UUID du joueur entre dans `challengeSwapInProgress` pour que le
   `BATTLE_STARTED_PRE` redéclenché ne rebascule pas.
5. Un saut de `server.execute` plus tard, l'UUID est retiré de l'ensemble de garde, et
   l'identifiant d'origine est restauré au saut suivant.

⚠️ Cette restauration est la partie fragile. Si le serveur se bloque ou si l'entité se
décharge entre les étapes 3 et 5, le TrainerMob conserve l'identifiant de défi
définitivement. `CapListener` possède une réparation précisément pour cela : quand un joueur
interagit avec une entité portant un identifiant de défi pour un champion qu'il **n'a pas**
battu normalement, il restaure l'identifiant normal — en préservant tout suffixe de
dimension — et traite l'interaction comme un combat normal, en journalisant :

```text
[CobbleCap] Entity has stuck challenge ID 'gym_leader_pierre_challenge' but player hasn't beaten 'gym_leader_pierre_0395' — restoring to 'gym_leader_pierre_0395'
```

Une victoire en défi enregistre `challengeBeaten`, horodate le cooldown, relève la limite
seulement si `challengeCapGranted > 0`, et accorde `cobblecap:challenge/<clé>` — ainsi que
`cobblecap:milestone/challengers_complete` dès que chaque entrée ayant un `challengeId` figure
dans `challengeBeaten`. Les combats
de défi sautent entièrement le contrôle de limite d'équipe — c'est du contenu post-game et
la limite n'est pas censée s'y appliquer. Un défi est **à usage unique** : une fois dans
`challengeBeaten`, une nouvelle interaction est refusée avec la réplique post-victoire.

## Ce que fait une victoire normale

`handleVictory`, dans l'ordre :

1. `addChampionBeaten(id)` — idempotent.
2. `raiseCapLevel(capGranted)` — montée seule.
3. En cas de hausse : une annonce à tout le serveur
   (`%s a vaincu le champion %s ! Sa limite est maintenant %d.`) et un message personnel
   `Vous avez vaincu %s ! Votre limite de niveau est maintenant %d.`
4. Sans hausse mais à la première victoire : la ligne personnelle devient
   `Vous avez déjà vaincu %s.` à la place, ce qui se lit bizarrement pour une vraie première
   victoire et constitue une verrue connue.
5. Première fois seulement : accorder `cobblecap:champion/<clé>`, déclencher le webhook
   Discord, et si tous les champions normaux sont désormais battus, accorder
   `cobblecap:milestone/badges_complete`.
6. Première fois seulement, et seulement si `id` vaut `champion_regis_challenge` : lancer la
   promotion au rang de Maître de la Ligue.
7. Synchroniser le HUD du client.

L'advancement est accordé en dispatchant `advancement grant <joueur> only
cobblecap:<catégorie>/<clé>`, où `<clé>` est le **nom affiché** en minuscules avec les
espaces remplacés par des tirets bas. Le mod livre des JSON d'advancement sous
`data/cobblecap/advancement/{champion,challenge,milestone}/` pour le casting d'Arkensia ;
renomme un champion et l'octroi cible silencieusement un advancement qui n'existe pas.

La complétion des quêtes FTB n'est **plus** forcée. `ChampionManager.triggerFtbQuest` et
`triggerFtbQuestById` existent toujours mais rien ne les appelle depuis le chemin de
victoire : le commentaire du source explique que
`Quest.forceProgress → Reward.forceProgress → claimReward` dupliquait parfois la récompense
de badge, et que le mod s'appuie désormais sur le propre
`AdvancementTask.autoSubmitOnPlayerTick` de FTB, qui observe l'advancement accordé. Ces deux
méthodes sont donc du code mort conservé pour référence.

## Classique et compétitif

Battre le champion final promeut le joueur **Maître de la Ligue**, et il existe deux flux
distincts selon le score du joueur sur le scoreboard `cobblecap_mode` : `2` signifie
**RANKED** (compétitif), tout le reste — y compris l'absence de score et l'absence
d'objectif — signifie **CLASSIC**. `/cobblecap mode` écrit ce score.

### Classique

Écrit dans `LegendsStorage` (`config/cobblecap_legends.json`), le Panthéon permanent.

- Aucune légende encore : l'équipe du joueur est figée, un `LegendEntry` est ajouté avec
  `firstEver` à vrai si personne n'avait jamais été couronné, l'annonce est diffusée, le
  webhook Discord part, et les scoreboards Conseil 4 sont reconstruits.
- La légende existe et un chrono capturé bat le `bestTimeMs` enregistré (ou aucun temps
  n'était enregistré) : l'entrée est remplacée avec le nouveau temps et tout le monde voit
  `🏆 Steve bat son record : 18m42s !`
- La légende existe et la course a été plus lente : le record tient et tout le monde voit
  `⏱ Steve a fini en 24m05s (record perso : 18m42s)`

Ces deux dernières chaînes sont des `Text.literal` en français dans le source, donc un
client anglais voit du français. Les quatre annonces `league_master*`, elles, passent par
les fichiers de langue et sont bien traduites.

### Compétitif

Écrit dans `TenureStorage` (`config/cobblecap_tenures.json`) et **ne touche jamais au
Panthéon**. Une victoire compétitive ouvre **toujours** un nouveau règne, même pour un joueur
qui reprend son propre titre, de sorte que le décompte des tentatives ratées repart de zéro
à chaque règne.

`startNewTenure` horodate le `endedAt` du règne courant précédent et en ajoute un nouveau.
Au plus un règne a `endedAt == 0` à un instant donné. Puis, dans l'ordre : une annonce
(`👑 … entre dans l'histoire comme PREMIER Maître de la Ligue Compétitif` pour le tout
premier, `👑 … détrône <précédent> et devient le nouveau Maître de la Ligue` ensuite — des
littéraux français là encore), un webhook Discord construit depuis un `LegendEntry`
**synthétique** qui n'est jamais stocké, une reconstruction des scoreboards, un
[morph de dresseur RCT](champion-morphing.fr.md) mis en file, et le spectacle de feux
d'artifice.

### Les défaites compétitives

Quand `BATTLE_VICTORY` ne rapporte aucun joueur gagnant, `handleRegisRankedLoss` vérifie si
l'identifiant du dresseur PNJ gagnant est `champion_regis_challenge` et si le joueur perdant
est en mode compétitif. Les deux vrais, et `Tenure.recordFailure` incrémente le compteur de
ce challenger contre le règne **courant** et reconstruit les scoreboards. Les défaites
classiques ne sont pas comptées, et sans règne courant l'appel est un no-op silencieux.

## Les chronos

`ChronoManager` est une table d'UUID de joueur vers horodatage de départ, persistée dans
`config/cobblecap_chronos.json` à chaque mutation. Une course survit donc à un crash ou à un
redémarrage et continue de défiler depuis son départ d'origine — le mod ne met pas la
pendule en pause pendant que le serveur est arrêté.

Les durées s'affichent `18m42s`, ou `1h02m03s` au-delà de l'heure, et `—` pour une valeur
négative.

Les chronos sont en pratique démarrés et arrêtés par des déclencheurs externes : une
commande d'entrée de dimension CheeseGuard lance `/cobblecap chrono start`, et une commande
de récompense FTB Quests lance `/cobblecap chrono stop`. Ils sont aussi arrêtés
implicitement — `tryGrantLeagueMaster` appelle lui-même `ChronoManager.stop`, donc battre le
champion final clôt la course qu'un `stop` ait été dispatché ou non.

Deux annulations automatiques, toutes deux enregistrées dans `CobbleCapMod.onInitialize` :
quitter une dimension `…:conseil4*` vers un endroit qui n'est pas lié au conseil4, et se
déconnecter.

Les commandes de chrono sont documentées dans
[commandes statues, chrono et champion](commands-statues.fr.md#cobblecap-chrono-start-player),
y compris la condition stricte sur `chrono stop`.

## Le Panthéon

`config/cobblecap_legends.json`, en ajout seul en usage normal, écrit de façon atomique via
un fichier `.tmp` et un `Files.move` :

```json
{
  "legends": [
    {
      "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
      "playerName": "Steve",
      "completedAt": 1778000000000,
      "firstEver": true,
      "bestTimeMs": 1122000,
      "mode": "classic",
      "team": [
        {
          "species": "garchomp",
          "displayName": "Garchomp",
          "nickname": "",
          "level": 78,
          "shiny": false,
          "ball": "ultra",
          "nature": "adamant",
          "gender": "MALE",
          "form": "",
          "aspects": []
        }
      ]
    }
  ]
}
```

`isTest` n'est écrit que s'il vaut `true`, et `bestTimeMs` seulement s'il est supérieur à
zéro, ce qui garde un historique lisible. `mode` est toujours écrit pour que le code des
scoreboards n'ait jamais à devenir devin.

Le tableau `team` est un instantané pris à la promotion, lu par réflexion sur l'équipe
Cobblemon : identifiant showdown de l'espèce, nom affiché, surnom, niveau, shiny, ball de
capture, nature, genre, l'aspect de forme principal et la liste brute des aspects. Les
aspects sont ce qui permet de refaire apparaître une statue plus tard avec la même variante
régionale ou cosmétique. Chaque champ se dégrade en `""` ou `0` au lieu de lever une
exception, donc un instantané partiel est possible, et un Pokémon dont `species` est vide ne
sera pas rendu en statue.

Les entrées de test issues de `/cobblecap statue test` portent `isTest: true` et sont
exclues de `getAll()`, `hasLegend()`, `isEmpty()`, de toutes les annonces, du webhook et des
scoreboards Conseil 4. Seuls `getAllIncludingTests()` et le sous-système de statues les
voient.

## Les classements Conseil 4

`Conseil4Boards` maintient quatre objectifs de scoreboard vanilla, créés à
`SERVER_STARTED` et reconstruits après chaque promotion, record, défaite compétitive et
`chrono stop`. Chaque reconstruction **supprime et recrée** l'objectif, pour qu'aucun nom
périmé ne subsiste.

| Objectif | Mode | Score écrit | Ordre dans la barre latérale |
|---|---|---|---|
| `conseil4_first_finish` | classique | `nombre − index` sur `completedAt` croissant | le plus ancien vainqueur en haut |
| `conseil4_fastest_time` | classique | `−secondes` | le plus rapide en haut |
| `conseil4_ranked_champions` | compétitif | `startedAt / 1000` | le champion en titre en haut |
| `conseil4_ranked_failures` | compétitif | le nombre d'échecs | le plus d'échecs en haut |

Un cinquième objectif, `cobblecap_mode`, est créé d'avance ici pour que `/cobblecap mode`
n'ait jamais à l'amorcer. Ses noms affichés sont des littéraux français.

Les scores inversés et négatifs sont la façon dont une barre latérale vanilla — qui trie
toujours en décroissant — finit par afficher l'ordre voulu. Une conséquence à connaître :
`conseil4_fastest_time` contient des scores **négatifs**, donc tout consommateur externe qui
le lit brut doit les renégativer. Une autre : les scores sont indexés par **nom de joueur**,
pas par UUID, donc un changement de pseudo orpheline une ligne jusqu'à la reconstruction
suivante.

Le titre du tableau des échecs est dynamique, `§bConseil 4 — Échecs face à <champion>`, et
il est remis à zéro implicitement à chaque nouveau règne, puisqu'il est reconstruit depuis
`current().failedAttempts` et qu'un nouveau règne démarre vide.

### Export JSON pour CobbleCheeseScore

Chaque tableau écrit aussi un fichier sous `config/` :

| Fichier | Tableau |
|---|---|
| `cobblecheesescore_external_conseil4_first.json` | premiers vainqueurs |
| `cobblecheesescore_external_conseil4_fastest.json` | meilleurs temps |
| `cobblecheesescore_external_conseil4_ranked_champions.json` | historique des règnes |
| `cobblecheesescore_external_conseil4_ranked_failures.json` | échecs face au champion en titre |

Les quatre partagent une forme : `generatedAt`, `source: "cobblecap"`, `mode`, `kind`,
`count`, et un tableau `ranking` d'objets portant au minimum `rank`, `playerUuid` et
`playerName`. Les exports des temps et des champions ajoutent `bestTimeMs` et un
`bestTimeFormatted` déjà formaté ; l'export des échecs ajoute `failures`, `lastFailAt`,
l'identité du champion et un `dynamicTitle` que CobbleCheeseScore utilise pour remplacer le
titre configuré de son tableau.

L'écriture est atomique via un voisin `.tmp`. Ces fichiers sont **produits** par CobbleCap
et **lus** par [CobbleCheeseScore](../../CobbleCheeseScore/) — CobbleCap n'a aucune
dépendance de code vers lui, et si CobbleCheeseScore est absent les fichiers sont
simplement écrits et ignorés.

Noter que `initObjectives` appelle `rebuild` au démarrage. Une version antérieure y écrivait
des tableaux vides, ce qui blanchissait les tuiles du dashboard après chaque redémarrage ;
la reconstruction depuis le stockage est le correctif.

## Le spectacle de feux d'artifice

Une victoire compétitive appelle `FireworksManager.triggerShow`, qui lance
`fireworksCount` fusées espacées de `fireworksIntervalMs` dans la zone CheeseGuard nommée
par `fireworksZoneId`, avec un plafond de `fireworksDurationSec`. C'est désactivé par défaut
(`fireworksEnabled: false`) et sans effet sans identifiant de zone. CheeseGuard est atteint
par réflexion, donc la fonctionnalité se dégrade en néant quand CheeseGuard est absent.
Règle le tir avec `POST /api/v1/cobblecap/fireworks/test`, qui contourne le drapeau
d'activation — voir
[l'API dashboard](dashboard.fr.md#post-apiv1cobblecapfireworkstest).

## Ce que tu dois changer pour ton propre serveur

| Quoi | Où | Pourquoi ça compte |
|---|---|---|
| `champions.json` | `config/cobblecap/champions.json` — livré **vide** | sans entrées, rien ne conditionne la limite et rien ne la relève |
| `champion_regis_challenge` | `ChampionManager.handleVictory` ligne 548, `handleChallengeVictory` ligne 605, `handleRegisRankedLoss` ligne 792, `StatueCommand.finalizeChrono` ligne 541 | l'identifiant du champion final est codé en dur à quatre endroits. Un serveur dont le dernier champion porte un autre identifiant ne promeut jamais de Maître de la Ligue et ne compte jamais de défaite compétitive |
| `datapackName = "CacademyMons"` | `RctTrainerMorpher.java` ligne 57 | voir [le morphing du champion](champion-morphing.fr.md#ce-que-tu-dois-changer) — le morph ne peut pas fonctionner tant que cela ne correspond pas à ton datapack |
| les JSON d'advancement | `data/cobblecap/advancement/**` | indexés sur les noms affichés des champions d'Arkensia ; les tiens ont besoin de leurs propres fichiers |
| `cobblecap.msg.condition_progress` | les fichiers de langue | il dit *Tu dois d'abord parler à Alfred*, qui est le PNJ de quête d'Arkensia |
| les titres de scoreboard et plusieurs annonces | `Conseil4Boards`, `ChampionManager`, `StatueCommand` | des littéraux français, pas des clés traduisibles |

---

← [La limite de niveau](level-cap.fr.md) · [Le morphing du champion](champion-morphing.fr.md) →
