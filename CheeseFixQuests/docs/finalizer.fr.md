[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](finalizer.md)

# Le balayage de finalisation

Les redirections décrites dans [architecture](architecture.fr.md) envoient la progression au
bon endroit. Elles ne corrigent pas une seconde famille de problèmes, sans rapport : la
progression correctement écrite mais sur laquelle personne n'*agit* ensuite. Une tâche reste
à 40 sur 40 et la quête demeure verrouillée. La dernière tâche d'une quête se termine et la
récompense en réclamation automatique ne tombe jamais. Le joueur se déconnecte, se reconnecte,
et tout se débloque tout seul.

`QuestFinalizerHook` est la réponse à cette classe de bugs. Toutes les 200 ticks, il parcourt
les joueurs connectés et pousse FTB Quests à faire les étapes qu'il a sautées, sur le thread
serveur, sans que personne ait à se reconnecter.

C'est la partie la plus intrusive du mod et celle qui te surprendra le plus. Chacune de ses
quatre phases a son propre interrupteur d'arrêt dans
[`cheesefixquests.json`](configuration.fr.md#les-quatre-phases-du-finaliseur).

---

## Pourquoi la progression se bloque

Deux mécanismes, tous les deux dans FTB Quests.

**`TeamData.setProgress` court-circuite sur une valeur inchangée.** Quand le nouveau compte
égale le précédent, la méthode sort immédiatement et saute la cascade
`markTaskCompleted → task.onCompleted → completed.put`. Une tâche peut donc porter
`taskProgress[id] = max` alors que `completed[id]` est tout bonnement absent.
`task.isCompleted(td)` lit la map `completed` : il répond `false` pour toujours, et
`Quest.isCompletedRaw` — qui vérifie en ET chaque tâche — répond `false` aussi. La quête est
finie et le système ne peut pas le voir.

**Certains écrivains contournent complètement la cascade.** `KeyValueReward.claim` appelle
directement `TeamData.addProgress` sans déclencher la complétion de tâche. Une quête-portail
qui compte 70 récompenses enfants peut atteindre son seuil sans un seul événement de
complétion.

Les deux états sont invisibles pour un joueur : le carnet montre une barre pleine sur une
quête qui refuse de donner sa récompense.

---

## Les quatre phases

Les quatre tournent dans `QuestFinalizerHook.tick`, enregistré sur
`ServerTickEvents.END_SERVER_TICK`. Le hook compte les ticks et ne fait absolument rien dans
199 cas sur 200 — `RESCAN_INTERVAL_TICKS = 200`, donc une fois toutes les 10 secondes. Quand
FTB Quests n'est pas chargé, le tout premier test (`FTBQ_LOADED`) sort et le hook ne coûte
rien.

Le balayage entier s'arrête définitivement dès que `ServerLifecycleEvents.SERVER_STOPPING` se
déclenche. C'est important : sans cela le dernier balayage pourrait muter un `TeamData`
pendant que le thread de sauvegarde le lit.

### Phase A — réanalyse de l'inventaire

`enableInventoryRescan`, défaut `true`.

Appelle `FTBQuestsInventoryListener.detect(player, ItemStack.EMPTY, 0L)` par réflexion, pour
chaque joueur connecté. C'est le scanner passif de tâches d'item propre à FTB ; le relancer
revalide les `ItemTask` pour les items entrés dans l'inventaire par un chemin que l'écouteur
de slots vanilla n'a jamais vu — API de mods, insertions NBT directes, échanges Cobblemon.

La méthode est résolue paresseusement dans `tryArmPhaseA()` et retentée à chaque balayage
jusqu'à réussir, ce qui explique qu'un échec transitoire au démarrage soit journalisé une
fois en WARN puis oublié :

```text
[CheeseFixQuests] Phase A (detect) armed — sweep every 200 ticks.
```

### Phase B — balayage `submitTask` par tâche

`enableTaskSubmitSweep`, défaut `true`.

Pour chaque quête dont l'équipe peut démarrer les tâches, et chaque tâche dont
`checkOnLogin()` vaut `true`, appeler `task.submitTask(td, player)`. C'est l'appel que FTB
fait à la connexion : il atteint donc les types de tâches non liés aux items — élimination,
biome, position, statistique, progrès, dimension, observation, personnalisé. Les tâches déjà
terminées sortent immédiatement sur leur propre garde `isCompleted`.

Une tâche qui lève une exception est avalée et le balayage continue. Une définition de quête
cassée ne doit pas arrêter les autres.

### Phase C — la passe de réparation

`enableQuestFinalization`, défaut `true`.

La plus substantielle. Elle résout un `TeamData` pour le joueur — le sien quand il n'est pas
en groupe, mais celui **du groupe** quand il l'est, parce que la recherche aboutit à
`PlayerTeam.getTeamId()`, qui renvoie l'identifiant de l'équipe effective (voir
[les faiblesses connues](architecture.fr.md#faiblesses-connues-et-compromis-assumés)). La
phase B travaille sur ce même enregistrement. Elle parcourt ensuite chaque quête en trois
sous-étapes, en sautant les quêtes déjà terminées et celles dont les dépendances ne sont
pas satisfaites.

**C0 — scan direct de l'inventaire pour les `ItemTask`.** L'écouteur d'inventaire de FTB est
attaché au conteneur d'écran propre au joueur, pas à celui d'un coffre : un clic-maj depuis un
coffre ne lui parvient jamais. Les phases A et B finissent toutes deux par passer par
`setProgress`, qui court-circuite. C0 compte donc les correspondances lui-même : il parcourt
l'inventaire du joueur et appelle `ItemTask.test(stack)` — le prédicat propre à la tâche, donc
la correspondance sur les composants NBT se comporte exactement comme FTB l'entend — et somme
les tailles de piles.

Avec `matches` en main :

- `matches >= max` → `markTaskCompleted(task)` directement
- `matches > current` → `setProgress(task, matches)`

Les deux sous `synchronized (td)`, avec les tests de complétion et de dépendances revérifiés à
l'intérieur du verrou.

**C1 — progression de tâche orpheline.** Pour chaque tâche, item ou non : si
`progress >= maxProgress` mais que `isCompleted(task)` est faux, appeler
`markTaskCompleted(task)`. C'est précisément ce que la cascade court-circuitée aurait dû
faire. `markTaskCompleted` déclenche de façon synchrone `task.onCompleted`, qui écrit le
`completed[taskId]` manquant et, s'il s'agissait de la dernière tâche de la quête, enchaîne
sur `Quest.onCompleted → checkAutoCompletion → claimReward`.

**C2 — finalisation attrape-tout.** Si après C0 et C1 toutes les tâches sont terminées mais la
quête elle-même n'est toujours pas marquée, construire un `QuestProgressEventData` et appeler
`quest.onCompleted(ev)` une fois, sous `synchronized (td)`. La garde `!isCompleted` propre à
FTB en tête de `onCompleted` rend l'opération idempotente.

À l'intérieur d'un balayage, un `Set<Long>` indexé sur
`quest.id ^ teamId.getMostSignificantBits()` empêche d'examiner deux fois le même couple
(quête, équipe) quand plusieurs membres d'une équipe sont connectés. Le set est vidé au début
de chaque balayage.

### Phase D — resynchronisation du Pokédex Cobblemon Quests

`enableCobblemonPokedexResync`, défaut `true`. Ne fait rien si `cobblemon_quests` n'est pas
chargé.

Relance `CobblemonQuestsEventHandler.triggerPokeDexUpdate(uuid)` pour chaque joueur connecté.
Les déclencheurs naturels de ce gestionnaire sont `PLAYER_JOIN` et les événements de
changement de Pokédex ; si l'un est manqué — ordre de chargement des mods, course avec un
autre écouteur — la progression Cobblemon Quests du joueur prend du retard jusqu'à sa
prochaine connexion. Le relancer n'appelle que
`increaseHaveRegistered → TeamData.addProgress`, que la phase C finalise ensuite.

La phase D est atteinte entièrement par réflexion, et elle distingue deux sortes d'échec. Un
`eventHandler` nul est transitoire — l'initialiseur statique de Cobblemon Quests n'a peut-être
pas encore tourné — donc elle retente au balayage suivant. Une classe, un champ ou une méthode
absents sont définitifs : le drapeau `phaseDPermanentlyFailed` est posé, un seul WARN est
écrit, et il n'y a plus aucune tentative.

```text
[CheeseFixQuests] Phase D (Cobblemon Pokédex resync) armed.
[CheeseFixQuests] Phase D permanently disabled (class shape mismatch): java.lang.NoSuchMethodException: …
```

La seconde ligne est ce que tu verras quand Cobblemon Quests se mettra à jour et déplacera
cette méthode. C'est un avertissement, pas un crash : le reste du mod continue de fonctionner.

---

## Le contrat anti-duplication

Le balayage appelle `Quest.onCompleted`, qui peut réclamer des récompenses. Une récompense
réclamée deux fois donne l'item deux fois, ou exécute sa commande deux fois. Le balayage doit
donc être sûr contre lui-même, contre le chemin événementiel propre à FTB qui se déclencherait
pour la même quête au même tick, et contre les autres threads.

Trois garanties s'empilent :

1. **`TeamDataClaimRewardSyncMixin`** rend atomique le test-puis-écriture de
   `TeamData.claimReward`. `claimedRewards` est une map fastutil `Object2LongMap` sans sûreté
   vis-à-vis des threads ; dans le bytecode d'origine la garde `containsKey` et le `put` sont
   séparés, donc deux threads qui atteignent la même clé peuvent passer tous les deux la garde
   et exécuter tous les deux la remise réelle. Le mixin redirige l'appel `containsKey` vers un
   bloc `synchronized (map)` qui teste et réserve la clé en une seule étape. Toutes les
   réclamations de récompense du jeu, d'où qu'elles viennent, y passent.
2. **`synchronized (td)`** autour de la finalisation dans C0, C1 et C2, qui sérialise ce hook
   contre lui-même.
3. **Les gardes `!isCompleted` propres à FTB**, revérifiées à l'intérieur de chaque verrou.

Le hook ne réclame jamais une récompense directement. Le seul chemin vers une réclamation est
la cascade de FTB, couverte par le point 1.

C'est aussi pourquoi `enableClaimRewardLock` doit rester à `true`. Le couper restaure le
comportement d'origine non gardé, et avec le balayage par-dessus la fenêtre de course est plus
large que celle de FTB Quests seul, pas plus étroite. Le commentaire de configuration nomme
l'incident pour lequel il a été écrit : une récompense de badge donnée deux fois, à deux
secondes d'écart, avec une seule entrée dans les données persistées parce que l'écriture du
premier thread avait écrasé celle du second.

---

## Le journal d'audit

`auditQuestEvents`, défaut `false`, écrit `config/cheesefixquests_audit.log`. Active-le quand
tu as un signalement de récompense dupliquée ou une quête qui refuse de se finaliser, et
coupe-le une fois l'enquête terminée — le balayage émet une ligne par joueur et par balayage,
donc le fichier grossit régulièrement même quand tout va bien.

Chaque ligne est un horodatage à la milliseconde, un nom d'événement, puis des champs
`clé=valeur`. Le nom du thread est sur chaque ligne, et c'est tout l'intérêt : une vraie
course entre threads peut être reconstituée depuis ce seul fichier.

| Événement | Émis quand |
|---|---|
| `PHASE_C_SWEEP` | fin de la phase C pour un joueur — toujours, même avec tous les compteurs à zéro, pour prouver que le balayage tourne |
| `ITEM_REPAIR` | C0 a trouvé une `ItemTask` complète et appelé `markTaskCompleted` |
| `ITEM_SET` | C0 a trouvé une progression partielle et l'a poussée avec `setProgress` |
| `TASK_REPAIR` | C1 a réparé une tâche orpheline |
| `FINALIZE` | C2 a appelé `Quest.onCompleted` |
| `FINALIZE_SKIP` | la phase C a levé une exception sur une quête ; porte la classe et le message |
| `CLAIM_RESERVED` | le verrou a réservé une clé de récompense — la réclamation passe |
| `CLAIM_BLOCKED` | le verrou a vu la clé déjà présente — une seconde réclamation a été arrêtée ici |
| `POKEDEX_RESYNC` | la phase D s'est déclenchée pour un joueur |

Les identifiants de quête et de tâche sont écrits en hexadécimal majuscule, comme dans le
carnet et les fichiers `.snbt`.

```text
2026-10-29 14:02:11.418 PHASE_C_SWEEP thread=Server thread player=Steve/… visited=812 itemScans=14 itemRepairs=0 itemSets=1 orphanRepairs=0 finalizes=0
2026-10-29 14:02:11.420 ITEM_SET thread=Server thread quest=3C1A4E7B9D2F6058 task=5A77… team=… player=Steve old=3 new=7
2026-10-29 14:07:52.004 CLAIM_BLOCKED thread=CobbleCap-Save key=QuestKey[…]
```

Un `CLAIM_BLOCKED` sur un thread autre que `Server thread` est la course pour laquelle le
mixin existe, prise en flagrant délit. C'est la ligne à coller dans un rapport de bug.

Les écritures sont sérialisées sur un verrou unique et vidées ligne par ligne, donc un crash
conserve la piste. Le writer est ouvert paresseusement au premier événement et fermé à la fois
par `ServerLifecycleEvents.SERVER_STOPPING` et par un hook d'arrêt de la JVM. Si l'ouverture
du fichier échoue, la journalisation d'audit se désactive elle-même après un WARN plutôt que
de retenter à chaque ligne.

---

## Coût, et quoi faire si ça pèse

Le balayage est en `O(joueurs connectés × quêtes)` toutes les 10 secondes, plus un scan
d'inventaire par `ItemTask` inachevée par quête par joueur dans C0. Le commentaire du code
annonce moins d'une milliseconde pour 50 joueurs et 1000 quêtes. Cette affirmation n'est pas
mesurée indépendamment ici, et le scan d'inventaire imbriqué de C0 est la partie la plus
susceptible de coûter davantage que le commentaire ne le suggère sur un gros carnet.

Si un profileur de temps de tick désigne `QuestFinalizerHook`, coupe les phases en partant du
bas : `enableCobblemonPokedexResync`, puis `enableTaskSubmitSweep`, puis
`enableInventoryRescan`. Garde `enableQuestFinalization` — c'est celle qui répare de vraies
casses — et garde `enableClaimRewardLock` quoi que tu coupes d'autre. Chaque drapeau exige un
redémarrage.

---

← [La migration](migration.fr.md) · [Architecture](architecture.fr.md) →
