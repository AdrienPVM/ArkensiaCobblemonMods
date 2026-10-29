[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

CheeseFixQuests, c'est neuf mixins et deux classes utilitaires. Le mod n'enregistre aucun
bloc, aucun item, aucun paquet réseau et aucun module CheeseDash. Tout ce qu'il fait, il le
fait en se plaçant devant une question précise que FTB Quests pose sans arrêt : *à quelle
équipe appartiennent les données de ce joueur ?*

---

## Ce que FTB Quests fait par défaut, et pourquoi c'est un problème

FTB Quests stocke la progression par équipe, dans un objet `TeamData` indexé par UUID
d'équipe. FTB Teams donne à chaque joueur une **équipe de joueur** permanente, et lui permet
en plus de rejoindre une **équipe de groupe** (*party*). Quand un joueur est dans un groupe,
`getTeamForPlayerID(uuid)` de FTB Teams renvoie le groupe — l'équipe *effective* du joueur —
et FTB Quests lit et écrit donc le `TeamData` de ce groupe.

Cette conception est correcte pour son usage prévu : un modpack où le groupe est une équipe
coopérative avec une seule ligne de quêtes partagée. Elle est fausse pour tout serveur où les
groupes existent pour autre chose — protections de terrain, chargement de chunks, un canal de
chat partagé, une guilde. Ces joueurs n'ont pas demandé à partager un carnet, et la première
fois que l'un d'eux tue un boss la quête disparaît de celui de tous les autres.

FTB Teams expose aussi `getPlayerTeamForPlayerID(uuid)`, qui renvoie toujours l'équipe de
joueur permanente, groupes ou pas. Presque tout le mod tient en une idée : trouver chaque
endroit où FTB Quests appelle la première méthode, et le faire appeler la seconde.

---

## Portée : ce qui est couvert, et ce qui ne l'est pas

Point à traiter précisément, parce que la réponse honnête est « la plupart des chemins, pas
tous ».

**Couvert.** `BaseQuestFile.getTeamData(PlayerEntity)` et
`BaseQuestFile.getOrCreateTeamData(Entity)` sont les entonnoirs par lesquels FTB Quests
atteint la plupart du temps les données d'un joueur. Rediriger la résolution à l'intérieur de
ces deux méthodes rend individuels **tous les types de quêtes et tous les types de tâches** —
il n'y a nulle part dans ce mod de logique par type de tâche. La redirection se situe à la
couche de résolution du stockage, en dessous de toute notion du genre de tâche en cours
d'écriture.

**Non couvert par cette redirection, et corrigé séparément.** Trois sites d'appel résolvent
l'équipe eux-mêmes au lieu de passer par `BaseQuestFile`, et chacun a exigé son propre mixin :

- `FTBQuestsInventoryListener.detect` appelle la surcharge **indexée par joueur**
  `getTeamForPlayer(ServerPlayer)`, pas celle indexée par UUID. C'est le chemin d'écriture le
  plus fréquent de FTB — chaque ramassage, fabrication, clic-maj, lâcher et déplacement en
  conteneur — donc le manquer faisait fuiter la progression des tâches d'item vers le groupe à
  l'échelle de la minute.
- `ServerQuestFile.playerLoggedIn` prend l'équipe dans l'événement de connexion, pas dans une
  résolution.
- Cobblemon Quests et ExtraQuests appellent chacun FTB Teams directement depuis leur propre
  code.

**Toujours pas couvert.** Tout autre mod, ou tout futur chemin de code FTB, qui résout
l'équipe effective de son côté continuera d'écrire dans le groupe. Il n'y a pas de filet
attrape-tout ici. Le patron pour en ajouter un est plus bas, dans
[points d'extension](#points-dextension).

**Les quêtes déjà terminées par une équipe avant l'installation du mod** sont traitées par une
copie de données unique et séparée, pas par les redirections. La progression est migrée, pas
remise à zéro — mais la copie est incomplète d'une façon précise et documentée.
[La migration](migration.fr.md) la couvre en détail ; la version courte est que seule
`claimedRewards` enregistre quel joueur a fait quoi, donc pour les équipes qui ne sont plus des
groupes actifs, une quête terminée sans que sa récompense soit réclamée ne peut pas être
attribuée et ne survit pas.

---

## Organisation des packages

```text
com.cheesefixquests
├── CheeseFixQuestsMod.java        ModInitializer — chargement de la config, enregistrement des
│                                  commandes, hook de tick, hook d'arrêt, lignes de log de démarrage
├── CheeseFixQuestsConfig.java     9 drapeaux statiques + lecture/écriture Gson de config/cheesefixquests.json
├── CheeseFixQuestsCommand.java    l'arbre Brigadier /cheesefixquests (niveau 4)
├── QuestMigration.java            copie unique de la progression d'équipe vers le stockage individuel
├── logic
│   ├── QuestFinalizerHook.java    la passe de réparation en quatre phases, toutes les 200 ticks
│   └── QuestAuditLogger.java      journal d'investigation en ajout seul, config/cheesefixquests_audit.log
└── mixin
    ├── BaseQuestFileMixin.java            la redirection principale de résolution d'équipe
    ├── ServerQuestFileMixin.java          synchro de connexion + migration automatique + isPlayerOnTeam
    ├── ClientQuestFileMixin.java          isPlayerOnTeam côté client
    ├── TeamDataMixin.java                 bloque mergeData / mergeClaimedRewards
    ├── TeamDataAccessor.java              interface @Accessor sur les quatre maps de TeamData
    ├── TeamDataClaimRewardSyncMixin.java  test-et-réservation atomique sur claimReward
    ├── FTBQuestsInventoryListenerMixin.java  le chemin d'écriture des tâches d'item
    ├── CobblemonQuestsHandlerMixin.java   la résolution d'équipe propre à Cobblemon Quests
    └── ExtraQuestsTimerMixin.java         la réinitialisation du minuteur quotidien d'ExtraQuests
```

15 fichiers source, environ 1 920 lignes, dont `QuestFinalizerHook` représente plus d'un quart.

---

## Les mixins, un par un

Les neuf sont déclarés dans `cheesefixquests.mixins.json`, package
`com.cheesefixquests.mixin`, niveau de compatibilité `JAVA_21`, `required: true`,
`defaultRequire: 1`. `ClientQuestFileMixin` est dans la liste `client` ; les huit autres sont
dans `mixins`.

> ⚠️ Chacun d'eux vise les entrailles d'un mod tiers — classes, descripteurs de méthodes et,
> dans deux cas, champs privés. Rien là-dedans n'est une API publique. **Une mise à jour de
> FTB Quests, FTB Teams, Cobblemon Quests ou ExtraQuests peut casser ce mod, et le symptôme
> sera un crash au démarrage et non une dégradation en douceur**, parce que
> `cheesefixquests.mixins.json` est en `required: true` avec `defaultRequire: 1` : une
> redirection dont la méthode ou l'INVOKE cible n'existe plus fait échouer l'application du
> mixin.
>
> Les versions contre lesquelles ce mod est vérifié sont celles figées dans `build.gradle` et
> `localdeps/README.md` : **FTB Quests 2101.1.13**, FTB Teams 2101.1.2, FTB Library 2101.1.18,
> et en option Cobblemon Quests 1.1.14 et ExtraQuests 1.5.3. Considère toute autre version
> comme non testée.

| Mixin | Cible | Injection | Raison d'être |
|---|---|---|---|
| `BaseQuestFileMixin` | `BaseQuestFile` (FTB Quests) | deux `@Redirect` sur `TeamManager.getTeamForPlayerID` dans `getTeamData(PlayerEntity)` et `getOrCreateTeamData(Entity)` | le correctif principal : résoudre l'équipe de joueur plutôt que le groupe |
| `ServerQuestFileMixin` | `ServerQuestFile` | `@Inject` HEAD sur `playerLoggedIn` ; `@Redirect` sur `PlayerLoggedInAfterTeamEvent.getTeam()` ; `@Inject` HEAD annulable sur `isPlayerOnTeam` | lance la migration automatique, synchronise le bon `TeamData` vers le client à la connexion, et corrige le test d'appartenance à l'équipe |
| `ClientQuestFileMixin` | `ClientQuestFile` | `@Inject` HEAD annulable sur `isPlayerOnTeam` | côté client, `KnownClientPlayer.teamId()` est l'identifiant du groupe alors que `selfTeamData` porte celui de l'équipe de joueur ; l'écart faisait renvoyer vide à `getOrCreatePlayerData` et cassait le mode édition du carnet |
| `TeamDataMixin` | `TeamData` | `@Inject` HEAD annulable sur `mergeData` et `mergeClaimedRewards` | FTB fusionne la progression à l'entrée dans un groupe et les réclamations à la sortie ; avec la progression individuelle, les deux ne sont que de la contamination |
| `TeamDataAccessor` | `TeamData` | `@Accessor` ×4 | accès en lecture/écriture à `taskProgress`, `started`, `completed`, `claimedRewards` pour la migration, en contournant les setters et leurs cascades de complétion |
| `TeamDataClaimRewardSyncMixin` | `TeamData` | `@Redirect` sur `Object2LongMap.containsKey` dans `claimReward(UUID, Reward, long)` | rend atomique le test-puis-écriture ; voir [le contrat anti-duplication](finalizer.fr.md#le-contrat-anti-duplication) |
| `FTBQuestsInventoryListenerMixin` | `FTBQuestsInventoryListener` | `@Redirect` sur `TeamManager.getTeamForPlayer` dans `detect` | la surcharge indexée par joueur que la redirection principale ne couvre pas |
| `CobblemonQuestsHandlerMixin` | `CobblemonQuestsEventHandler` (Cobblemon Quests) | `@Redirect` sur `TeamManagerImpl.getTeamForPlayer` dans `getTeamData(ServerPlayerEntity)` | Cobblemon Quests résout l'équipe lui-même, donc la progression de capture/victoire/évolution/échange contournait toutes les autres redirections |
| `ExtraQuestsTimerMixin` | `com.vecoo.extraquests.util.Utils` (ExtraQuests) | `@Redirect` statique sur `TeamManager.getTeamForPlayerID` dans `questReset` | les minuteurs quotidiens réinitialisaient le `TeamData` vide du groupe, donc les quotidiennes ne revenaient jamais |

Toutes les redirections ont la même forme : si `forceIndividualProgress` est coupé, ou si le
joueur n'a pas d'équipe de joueur, appeler la méthode d'origine. Le mod se dégrade vers le
comportement standard, pas vers une exception.

### Remappage

Huit des neuf mixins déclarent `remap = false`, parce que leurs cibles vivent dans un package
tiers, hors de l'espace de noms mappé par Yarn. `BaseQuestFileMixin`, lui, **non** : il omet le
drapeau, et ses descripteurs de méthodes utilisent donc les noms Yarn
(`Lnet/minecraft/entity/player/PlayerEntity;`). `CobblemonQuestsHandlerMixin` et
`FTBQuestsInventoryListenerMixin` sont en `remap = false` mais référencent des classes
Minecraft dans leurs descripteurs : ils les écrivent donc avec les noms intermédiaires
(`Lnet/minecraft/class_3222;`). Mélanger les deux conventions dans un même fichier ne
fonctionnera pas ; aligne-toi sur celle du mixin qui t'entoure.

`build.gradle` reflète aussi cette séparation. FTB Quests / Teams / Library sont en
`modCompileOnly` (Loom les remappe en Yarn pour le développement), tandis que Cobblemon Quests
et ExtraQuests sont en `compileOnly` simple — un commentaire y note que `modCompileOnly` sur
Cobblemon Quests provoquait un conflit de remappage avec Cobblemon lui-même.

---

## L'état, et quand il atteint le disque

Trois fichiers, tous relatifs au répertoire de travail du serveur :

| Chemin | Écrit par | Quand |
|---|---|---|
| `config/cheesefixquests.json` | `CheeseFixQuestsConfig.save()` | créé au premier démarrage s'il est absent ; réécrit après `migrate` / `remigrate` et après la migration automatique |
| `config/cheesefixquests_audit.log` | `QuestAuditLogger` | ajouté et vidé ligne par ligne, uniquement tant que `auditQuestEvents` vaut `true` |
| les données de quêtes propres à FTB Quests | `ServerQuestFile.saveNow()` | à la fin d'une migration qui a copié quelque chose, et à la fin de `resetchapter` |

Le chemin de configuration est construit en `Path.of("config", "cheesefixquests.json")`, et non
via `FabricLoader.getConfigDir()`. Au lancement normal d'un serveur les deux désignent le même
dossier. Si tu démarres le serveur avec un répertoire de travail autre que la racine du
serveur, ce mod écrit sa configuration ailleurs que tous les autres mods.

Les valeurs de configuration sont lues une fois dans `onInitialize` vers des champs statiques.
Rien ne les recharge ; `load()` n'a pas de second appelant. `save()` sérialise les champs
statiques courants, ce qui signifie qu'une migration écrase toute modification faite à la main
dans le fichier serveur allumé.

`migrationDone` est un état persistant, pas un réglage, et il vit dans le même fichier que les
réglages. Supprimer le fichier de configuration pour remettre les drapeaux à zéro remet aussi
ce drapeau à zéro, ce qui relancera la migration à la prochaine connexion.

---

## Threading

Presque tout est sur le thread serveur.

- **Thread serveur** — `onInitialize`, tout l'arbre `/cheesefixquests`, le balayage de
  finalisation (`END_SERVER_TICK`), et la migration automatique (à l'intérieur de la séquence
  de connexion).
- **N'importe quel thread** — `TeamDataClaimRewardSyncMixin`. C'est tout l'objet du mixin. Une
  réclamation de récompense peut arriver d'un écouteur d'événement asynchrone, du worker d'un
  mod ou d'un thread de sauvegarde, et le bloc `synchronized (map)` de la redirection est ce
  qui rend le test-et-réservation atomique vis-à-vis de tous.
- **Writer d'audit** — appelé depuis le thread qui a levé l'événement ; toutes les écritures se
  sérialisent sur un verrou unique dans `QuestAuditLogger`.
- **Arrêt** — `ServerLifecycleEvents.SERVER_STOPPING` pose `serverStopping` pour que le balayage
  s'arrête avant la sauvegarde du monde, et ferme le writer d'audit. Un hook d'arrêt de la JVM
  vide le writer une seconde fois, par sécurité.

Il n'y a aucun thread HTTP nulle part : le mod n'a ni module dashboard ni surface REST.

---

## Points d'extension

**Un autre mod résout l'équipe effective et écrit de la progression de quête.** Ajoute un mixin
sur sa résolution, sur le modèle d'`ExtraQuestsTimerMixin` — c'est le plus petit du lot. Garde
le test sur `forceIndividualProgress` et le repli vers l'appel d'origine, ajoute la classe à
`cheesefixquests.mixins.json`, et déclare le mod dans `suggests` plutôt que `depends` pour que
le jar reste chargeable sans lui. Ajoute une dépendance `compileOnly` pour la vérification de
cible à la compilation : le processeur d'annotations de Mixin résout et valide chaque cible à la
compilation, même pour un `targets = "…"` sous forme de chaîne.

**Une nouvelle classe de bug de progression bloquée.** Ajoute une phase à
`QuestFinalizerHook` avec son propre drapeau de configuration, en suivant le patron existant :
un `try`/`catch` autour d'elle dans `tick`, un appel à `logOnce` en cas d'échec, et un compteur
dans la ligne d'audit `PHASE_C_SWEEP` si elle relève de la phase C.

**Un nouvel événement d'audit.** Une méthode statique sur `QuestAuditLogger` appelant
`write(EVENT, champs…)`, gardée au site d'appel par
`if (CheeseFixQuestsConfig.auditQuestEvents)`.

---

## Faiblesses connues et compromis assumés

**Les mixins visent les entrailles de mods tiers.** Répété depuis l'avertissement plus haut
parce que c'est la chose la plus importante à savoir : ce mod cassera lors d'une mise à jour de
FTB, bruyamment, au démarrage. C'est préférable à une casse silencieuse, mais cela veut dire que
tu figes tes versions FTB.

**Français codé en dur, aucun fichier de langue.** Chaque message de `CheeseFixQuestsCommand`
est un `Text.literal` avec une chaîne française et des codes de couleur `§`. Il n'y a pas de
dossier `assets/cheesefixquests/lang/`. Un administrateur anglophone reçoit du français.
Corriger cela demande de convertir quatre méthodes en `Text.translatable` et d'ajouter les
fichiers de langue.

**`migrate` annonce un succès quand le fichier de quêtes n'est pas chargé.** Quand
`ServerQuestFile.INSTANCE` est encore nul (ou que le gestionnaire de FTB Teams est
indisponible), `QuestMigration.migrateAll()` renvoie `0` et journalise un WARN, mais la commande met
`migrationDone = true` et affiche *Migration terminée: 0 joueur(s)*. Il faut ensuite `remigrate`
pour migrer réellement.

**La migration ne supprime jamais sa source.** Le caractère purement additif est ce qui rend
`remigrate` sans danger, et c'est aussi ce qui permet à un ancien enregistrement de groupe de
réensemencer une progression que tu avais volontairement retirée.

**`resetchapter` ne réinitialise que les quêtes terminées, et seulement pour les équipes de
joueur.** Les quêtes partiellement avancées gardent leur progression, et tout `TeamData` dont
l'équipe est un groupe ou n'existe plus est entièrement ignoré. La commande dé-réclame aussi les
récompenses : elle peut donc distribuer un second exemplaire d'une récompense si on l'utilise
hors de son scénario de réparation.

**Le scan d'inventaire de la phase C0 est le point chaud.** Pour chaque joueur connecté, pour
chaque `ItemTask` inachevée de chaque quête démarrable, il parcourt tout l'inventaire. Le
commentaire du code annonce que le balayage complet reste sous la milliseconde à 50 joueurs et
1000 quêtes ; ce chiffre n'est pas vérifié indépendamment ici.

**Deux recherches utilisent l'identifiant d'équipe effective.** Le `PlayerTeam.getTeamId()`
de FTB Teams renvoie l'identifiant de l'équipe sur laquelle le joueur est *effectivement* — le
groupe, s'il en a un ; seul `getId()` est l'identifiant permanent de l'équipe de joueur.
`QuestMigration` utilise `getId()` précisément pour cette raison, mais deux autres endroits
appellent `getTeamId()` sur l'équipe de joueur résolue : `QuestFinalizerHook.resolveTeamData`,
si bien que pour un joueur en groupe le balayage charge et répare le `TeamData` **du groupe**,
pas le sien ; et `ServerQuestFileMixin.isPlayerOnTeam`, qui pour un membre de groupe répond
donc `true` pour l'enregistrement du groupe et `false` pour le sien. Les joueurs hors groupe ne
sont pas concernés.

**Commentaires périmés.** Le javadoc de classe de `QuestFinalizerHook` dit que les phases
tournent « every 5 s » alors que `RESCAN_INTERVAL_TICKS = 200` fait 10 secondes. Les javadocs
des mixins se numérotent de façon incohérente : deux classes différentes se disent « Mixin 4 »,
une troisième « Mixin 8 », sur neuf. Fais confiance au code.

**`build.gradle` copie un fichier `LICENSE` qui n'existe plus.** Le mod embarquait autrefois un
`LICENSE` MIT local, qui contredisait le `PolyForm-Noncommercial-1.0.0` déclaré dans
`fabric.mod.json` ; il a été supprimé le 29 octobre 2026. Le bloc `jar { from("LICENSE") … }`
est désormais sans effet — le `from` de Gradle sur un chemin absent ne copie rien et n'échoue
pas. Le mod est sous la [PolyForm Noncommercial 1.0.0](../../LICENSE) du dépôt, comme tout le
reste ici.

**Aucun module dashboard.** Contrairement à la plupart des mods de ce dépôt, CheeseFixQuests
n'enregistre aucun entrypoint `cheesedash` et n'a aucune surface REST ni WebSocket. Il ne dépend
pas du tout de CheeseDash. Tout se pilote depuis la console ou `cheesefixquests.json`.

---

← [Le balayage de finalisation](finalizer.fr.md) · [Dépannage](troubleshooting.fr.md) →
