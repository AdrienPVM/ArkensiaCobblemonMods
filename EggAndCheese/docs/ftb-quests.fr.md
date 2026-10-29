[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](ftb-quests.md)

# L'intégration FTB Quests

Une chasse aux œufs veut une liste à cocher, et FTB Quests est la liste que la plupart des
packs Cobblemon embarquent déjà. `ftb/FtbQuestsHook.java` — 733 lignes, le plus gros
fichier du mod — crée une quête par œuf, la valide quand un joueur trouve cet œuf, et
maintient les deux en phase à travers les éditions et les suppressions.

⚠️ **Tout le fichier est de la réflexion sur les internes de FTB Quests.** Il n'y a aucune
dépendance à la compilation et aucun contrat d'API. Chaque nom de classe, de méthode et de
champ est résolu par chaîne de caractères à l'exécution, contre
`dev.ftb.mods.ftbquests.*`. Ça fonctionne, et c'est aussi la partie la plus fragile du
mod : **cela cassera quand FTB Quests changera ses internes**, et la casse sera une
dégradation silencieuse plutôt qu'un plantage. Si les quêtes n'apparaissent plus après une
mise à jour du pack, c'est le premier endroit à regarder.

Tout ce qui suit est une observation sur le comportement de FTB Quests au moment où le
hook a été écrit, pas une spécification de FTB Quests.

## Conditions d'activation

Deux conditions doivent être vraies pour que quoi que ce soit se produise :

1. `ftbQuestsEnabled` vaut `true` dans [la configuration](configuration.fr.md#ftbquestsenabled).
2. `FtbQuestsHook.isAvailable()` renvoie `true`.

`isAvailable()` déclenche un `init()` unique protégé par un drapeau `initialized`. Il
commence par `FabricLoader.getInstance().isModLoaded("ftbquests")` et journalise, une
fois :

```text
[EAC-FTB] FTB Quests not present — hook disabled
```

Puis il résout chaque classe, méthode, champ et constructeur dont il a besoin. Tout échec
dans l'ensemble requis laisse `available` à faux et le hook inerte pour le reste de la
session ; les éléments optionnels se dégradent individuellement et journalisent ce qu'ils
ont trouvé. Un démarrage sain sur un pack avec FTB Quests produit quelque chose comme :

```text
[EAC-FTB] CustomTask available — egg quests will not be clickable by players
[EAC-FTB] setRawIcon available — quest icons enabled
[EAC-FTB] TranslationManager available — title sync enabled
[EAC-FTB] Network sync available — live client updates enabled (sendToPlayers=true, create=true, editCtor=true)
[EAC-FTB] FTB Quests hook initialized — Java API ready
```

Ces cinq lignes sont le meilleur diagnostic de ce sous-système. Lis-les avant de deviner.

## Ce qu'il résout, et ce dont il se passe

| Élément | Requis | S'il manque |
|---|---|---|
| `ServerQuestFile`, `BaseQuestFile`, `Chapter`, `ChapterGroup`, `Quest`, `Task`, `CheckmarkTask`, `TeamData`, `QuestObjectBase` | oui | le hook se désactive entièrement |
| `TeamData.get(PlayerEntity)` | oui | idem — trouvé en balayant les méthodes statiques `get` à un argument |
| `CustomTask` | non | repli sur `CheckmarkTask`, que **les joueurs peuvent cocher eux-mêmes** |
| `QuestObjectBase.setRawIcon` | non | les quêtes apparaissent sans icône d'œuf |
| `TranslationManager` | non | les titres de quête peuvent ne pas atteindre les clients |
| `NetworkManager.sendToPlayers` d'architectury et les messages de réponse create/edit | non | les changements s'appliquent, mais les clients doivent se reconnecter pour les voir |

Le repli sur `CustomTask` est celui qui a des conséquences de jeu. Un `CheckmarkTask` est
une case à cocher manuelle : un joueur qui ouvre le livre de quêtes peut marquer l'œuf
comme trouvé sans l'avoir trouvé. Si ton log de démarrage dit `CustomTask not found`, ta
chasse fonctionne à la confiance.

## Créer une quête

`createQuest(chapterName, entry, server)` est appelé depuis deux endroits :
`POST /collectibles` et `AdminCollectibleItem.postPlacement` pour un œuf tout neuf. Il
renvoie l'id `long` de la quête, ou `0` en cas d'échec, et l'appelant stocke cet id sur
l'entrée sous `ftbQuestId`.

Ce qu'il construit :

- Le chapitre est résolu par `findOrCreateChapter`. La recherche se fait **par nom** ; un
  chapitre est créé quand aucun ne correspond, et plusieurs chapitres partageant le nom
  sont fusionnés en un seul, avec la trace
  `Merged N duplicate chapters into one 'oeufs'`.
- La position X de la quête vaut `existingQuests.size() * 2.5`, et Y vaut `0.0`. Toutes
  les quêtes d'œufs se posent sur une seule rangée horizontale, espacées de 2,5 unités.
  Avec cinquante œufs, cette rangée fait 125 unités de large ; un chapitre de livre de
  quêtes n'est pas conçu pour ça et tu passeras ton temps à faire défiler. Il n'y a ni
  retour à la ligne ni option de disposition.
- Le titre est le `title` de l'entrée, ou `Trouver : <id>` quand le titre est vide — noter
  l'espace insécable avant les deux-points, typographie française correcte et codée en dur
  dans le source Java. Le sous-titre est le `subtitle` de l'entrée.
- L'icône, quand `setRawIcon` a été résolu, est le véritable item d'œuf décoratif construit
  depuis l'ADN de l'entrée. Le livre de quêtes montre donc le vrai œuf procédural.
- `applyQuestAppearance` règle une échelle de 2× et une forme `none`.
- Une tâche est créée — `CustomTask` quand il est disponible, `CheckmarkTask` sinon — et
  reçoit le même titre que la quête.
- `onCreated()` est appelé sur les deux objets, la table d'ids est rafraîchie, les données
  en cache vidées, le fichier marqué modifié, les deux objets diffusés aux clients, et le
  fichier sauvegardé.

```text
[EAC-FTB] Created quest 'Trouver : cave_nord' for egg 'cave_nord' (questId=1234567890)
```

Chaque chemin d'échec renvoie `0` et journalise en erreur avec la trace d'appel. Une quête
qui échoue à se créer ne bloque pas l'œuf : l'entrée existe, `ftbQuestId` reste à `0`, et
le handler de découverte réessaiera.

### `ensureQuestExists`

Le chemin de rattrapage. `AdminCollectibleBlockEntity.handlePlayerDiscovery` l'appelle
quand le `ftbQuestId` de l'entrée vaut encore `0`, ce qui arrive quand l'œuf a été posé
avant l'activation de l'intégration FTB. La quête est créée au moment de la découverte,
l'id est réécrit sur l'entrée, et la tâche est validée dans le même mouvement : le premier
joueur à trouver un œuf préexistant ne perd donc pas sa coche.

## Valider une quête

`completeForPlayer(questId, player)` :

1. Récupère la quête du fichier par son id. Une quête absente journalise
   `Quest <id> not found — cannot complete for <player>` et sort.
2. Prend la liste des tâches de la quête. Une liste vide journalise
   `Quest <id> has no tasks`.
3. Résout le `TeamData` du joueur. Un `TeamData` nul journalise
   `No TeamData for player <name>`.
4. Appelle `markTaskCompleted` sur **la première tâche seulement**, puis sauvegarde le
   fichier de quêtes.

```text
[EAC-FTB] Completed quest 1234567890 for player 'Lia'
```

Seule la première tâche est validée. Si tu ajoutes à la main une seconde tâche à une quête
d'œuf, elle ne sera pas cochée. Conçois tes quêtes d'œufs avec une tâche chacune.

Passer par `TeamData` signifie que la validation est **par équipe**, avec la sémantique
propre de FTB Quests : sur un pack où les joueurs partagent une équipe, un membre qui
trouve l'œuf la valide pour tous. L'ensemble `discoveredBy` de l'œuf reste par joueur :
les statistiques du mod et le livre de quêtes peuvent donc légitimement divergir. Ce n'est
un bug ni de l'un ni de l'autre.

Sauvegarder le fichier de quêtes à chaque découverte mérite d'être relevé sur un serveur
chargé : un événement de chasse où cinquante joueurs trouvent des œufs produit cinquante
appels à `saveNow`.

## Mettre à jour et supprimer

`updateQuest(questId, title, subtitle, server)` est appelé depuis
`PUT /collectibles/:id` et depuis `POST /collectibles/:id/rename`, quand l'intégration est
active et que l'entrée a une quête. Il réécrit le titre et le sous-titre de la quête, et le
titre de la première tâche, puis diffuse une édition pour que les livres de quêtes ouverts
se rafraîchissent.

```text
[EAC-FTB] Updated quest 1234567890 title='La grotte du nord'
```

Noter ce que le renommage ne fait **pas** : le titre de la quête suit le `title` de
l'entrée, pas son id. Renommer `egg_014` en `cave_nord` laisse la quête afficher
`Trouver : egg_014` si l'entrée n'a pas de titre explicite, parce qu'`updateQuest` reçoit
`entry.getTitle()` — qui est toujours vide. Renseigne un titre, ou lance une
synchronisation complète.

`deleteQuest(questId)` passe par `ServerQuestFile.deleteObject(long)`, que FTB Quests
diffuse lui-même aux clients comme message de suppression. C'est pourquoi la quête d'un œuf
supprimé disparaît d'un livre de quêtes ouvert sans reconnexion.

```text
[EAC-FTB] Deleted quest 1234567890
```

## Synchronisation complète

`POST /api/v1/eggs/config/sync-ftb` appelle `syncAll(server, registry)`. C'est une
reconstruction, pas une réparation :

1. Sortir immédiatement si le hook est indisponible ou si `ftbQuestsEnabled` est faux.
2. Vider le cache de chapitre.
3. Pour chaque entrée dont le `ftbQuestId` est non nul, supprimer cette quête et remettre
   l'id à zéro.
4. Rafraîchir la table d'ids, marquer le fichier modifié.
5. Parcourir `getEntriesSorted()` et créer une quête et une tâche neuves pour chacune, à
   `x = index * 2.5`, en réécrivant le nouvel id sur l'entrée.
6. Sauvegarder le fichier et marquer le registre modifié.

```text
[EAC-FTB] Full sync starting for chapter 'oeufs'...
[EAC-FTB] Full sync complete — 56 quests created
```

⚠️ **Une synchronisation complète détruit la progression des joueurs sur ces quêtes.** Les
anciennes quêtes sont supprimées et de nouvelles créées avec de nouveaux ids : chaque coche
gagnée par un joueur sur une quête d'œuf est perdue. Les enregistrements `discoveredBy` du
mod survivent — ils vivent dans le registre des œufs, pas dans FTB Quests — mais le livre
de quêtes repart de zéro. Utilise `syncAll` quand tu mets la chasse en place ou quand les
quêtes ont sérieusement dérivé, pas comme entretien de routine.

Toute la méthode est enveloppée dans un seul try/catch. Une exception en cours de route te
laisse avec une partie des quêtes supprimées et une partie recréées :

```text
[EAC-FTB] Full sync failed
```

Si tu vois cette ligne, lis la trace d'appel et prévois de relancer la synchronisation une
fois la cause corrigée.

## Utiliser l'advancement à la place

Si tu préfères que ce mod n'écrive pas dans ton fichier de quêtes, le hook est entièrement
facultatif. Laisse `ftbQuestsEnabled` à `false` et pilote tes quêtes depuis le déclencheur
d'advancement, qui est de la donnée de datapack ordinaire et n'a aucune réflexion à
proximité :

```text
Advancement ID: eggandcheese:egg_found
```

Une tâche FTB Quests de type **Advancement** pointant vers cet id se valide sur n'importe
quelle découverte d'œuf. Pour un œuf précis, écris ton propre advancement avec
`"conditions": {"egg_id": "cave_nord"}` — le détail est dans
[œufs décoratifs et stèles](display.fr.md#ladvancement).

Cette voie demande plus de travail à mettre en place, survit aux mises à jour de FTB
Quests, et c'est vers elle qu'il faut se replier quand le hook cesse de résoudre.

---

← [Œufs décoratifs et stèles](display.fr.md) · [Intégrité : doublons et orphelins](integrity.fr.md) →
