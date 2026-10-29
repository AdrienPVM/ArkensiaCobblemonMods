[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Une chose à savoir avant tout le reste : `cheesecompatmodsfix.mixins.json` pose
`"injectors": { "defaultRequire": 0 }`, ce qui signifie qu'**un mixin qui ne correspond plus
à sa cible est ignoré en silence**. Pas d'erreur, pas d'avertissement, pas de stack trace.
Le correctif cesse simplement d'exister.

Le mode d'échec caractéristique de ce mod n'est donc pas un crash. C'est un bug que tu
croyais corrigé qui revient, sans rien dans le log. Presque tous les symptômes ci-dessous
sont une variation sur ce thème.

---

## Symptôme : le build échoue avant de compiler

```text
* What went wrong:
A problem occurred evaluating project ':CheeseCompatModsFix'.
>
  CheeseCompatModsFix cannot be built without these third-party jars.

  Missing from localdeps/:
    - ftb-quests-fabric-2101.1.13.jar
```

**Cause.** FTB Quests et StarAcademy sont exclusifs à CurseForge. Gradle ne peut pas les
récupérer, et `build.gradle` vérifie la présence des deux fichiers à la phase de
configuration plutôt que de te laisser heurter cent erreurs `cannot find symbol` plus loin.

**Remède.** Télécharge le fichier **Fabric 1.21.1** de ce qu'il nomme depuis CurseForge et
mets-le dans `localdeps/` à la **racine du dépôt** — pas dans `CheeseCompatModsFix/` — avec
exactement le nom indiqué dans le message. Voir
[les prérequis de build](installation.fr.md#-le-build-exige-deux-jars-que-tu-dois-fournir).

Une version plus récente de l'un des deux compilera probablement, mais il faudra alors
changer le nom de fichier dans `CheeseCompatModsFix/build.gradle` aussi, parce que la
référence est un nom littéral et non un glob.

## Symptôme : le build échoue avec `Mixin target ... could not be found`

**Cause.** Un mixin qui désigne sa cible par une chaîne a quand même besoin de cette classe
sur le classpath de compilation. Le processeur d'annotations Mixin résout et valide chaque
cible à la compilation.

**Remède.** Ajoute le mod à `build.gradle` en `modCompileOnly` (ou `compileOnly`, si les
signatures de la classe n'entraînent pas de types Minecraft remappés). Épingle-le à un **id
de version** Modrinth, pas à un numéro de version — les numéros ne sont pas uniques sur
Modrinth et tu peux obtenir silencieusement le jar NeoForge. Le raisonnement est dans
[`localdeps/README.md`](../../localdeps/README.md).

## Symptôme : le mod ne se charge pas

Aucune ligne `[CheeseCompat] Loaded (v1.2.53)` nulle part dans le log.

**Cause et remède**, dans l'ordre à vérifier :

| Cause | Remède |
|---|---|
| Java sous 21 | `java -version` ; le mod déclare `java >=21` |
| Fabric Loader sous 0.16.0 | mets le loader à jour |
| Fabric API absent | `fabric-command-api-v2` est une dépendance dure ; installe l'API Fabric complète |
| Minecraft n'est pas en 1.21.1 | `minecraft ~1.21.1` — un serveur 1.21.4 refusera le jar |
| deux copies du jar dans `mods/` | supprime l'ancien `cheesecompatmodsfix-*.jar` ; Fabric échoue sur des ids de mods dupliqués au lieu d'en choisir un |

Si Fabric l'a refusé, la raison est sur le premier écran du log, avant l'initialisation de
tout mod, sous forme de `ModResolutionException` nommant la dépendance non satisfaite.

## Symptôme : un correctif pour un mod que j'ai installé ne marche pas

La ligne `Loaded (v1.2.53)` est là, mais rien de ce que ce mod est censé faire pour un mod
donné ne se produit.

**Cause, presque toujours : l'id du mod n'a pas correspondu.**
`CheeseCompatMixinPlugin` évalue `FabricLoader.isModLoaded("<id>")` pour quatorze chaînes
d'ids codées en dur. Un mod dont l'id n'est pas exactement l'une de celles-là est, du point
de vue de ce mod, non installé — et les mixins pour lui sont désactivés sans message.

**Remède.** Vérifie les ids :

```bash
grep -o 'isModLoaded("[^"]*")' src/main/java/com/cheesecompat/CheeseCompatMixinPlugin.java
```

contre le champ `id` du `fabric.mod.json` du mod installé. Un fork, un renommage, ou un mod
qui a changé d'id entre deux versions produisent tous ce symptôme. La liste complète est dans
[installation](installation.fr.md#optionnels--les-mods-quil-corrige).

**Deuxième cause : la famille ne journalise rien à l'activation.** Seuls certains correctifs
journalisent. Sers-toi de ce tableau :

| Si tu attends | Cherche |
|---|---|
| les correctifs CobbleSafari | quatre ou cinq lignes `[CheeseCompat] Safari …` / `Dungeon …` / `tatami` |
| les correctifs Enhanced Celestials | `EnhancedCelestials lunar warmup registered` puis une ligne `Warmed lunar data for …` par dimension |
| la restriction de `/stopbattle <player>` | `Restricted /stopbattle <player> to operators` |
| les correctifs Battle Tower | rien au démarrage — ils journalisent par combat, sur `startBattle` et `applyForcedLevelFallback` |
| les correctifs Daycare+ | rien. Teste le comportement à la place (voir plus bas) |
| les correctifs Legendary Monuments | rien. Mets une clé de config à `false` et teste |
| les correctifs client | rien. Teste visuellement |

**Troisième cause : le point d'injection a bougé.** Le mod visé a été mis à jour et la
méthode, la constante ou la lambda ciblée n'est plus là où elle était. `defaultRequire: 0`
rend ça silencieux. Il n'y a aucun moyen de le détecter depuis le log ; tu le détectes depuis
le symptôme. La version visée par chaque correctif est en haut de chaque
[page de catalogue](../README.fr.md#le-catalogue-des-correctifs).

## Symptôme : une clé de configuration n'a aucun effet

Précisément : `arcPhoneHealEnabled` ou `arcPhonePCEnabled` mise à `false`, et l'Arc Phone
fonctionne toujours.

**Cause 1 — tu as modifié le fichier serveur en marche.** La config est lue une fois, comme
première instruction d'`onInitialize()`. Il n'y a pas de commande de rechargement et pas de
surveillance de fichier. Pire : le démarrage suivant **réécrit** le fichier, donc une
modification faite serveur en marche est écrasée.

**Remède.** Arrête le serveur, modifie, démarre. Confirme avec la ligne de démarrage, qui
reprend les valeurs :

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=DISABLED, PC=enabled, Sanctuary fix=ON
```

`DISABLED` en capitales signifie que la config a bien été lue comme `false`. Si la ligne dit
`DISABLED` et que l'Arc Phone soigne quand même, la config va bien et le problème est le
mixin.

**Cause 2 — l'injection dans la lambda ne correspond plus.** `ArcPhoneHealMixin` et
`ArcPhonePCMixin` injectent dans `lambda$registerServerReceiver$1`. Les noms de lambdas sont
attribués par le compilateur dans l'ordre du source et ne sont pas une API : une version de
Legendary Monuments qui ne fait que réordonner les lambdas de cette méthode renomme la
cible. Avec `require = 0`, l'injection est sautée et la bascule cesse discrètement de
fonctionner.

**Remède.** Décompile ton jar Legendary Monuments, trouve `HealPacket`, et regarde comment
s'appelle la lambda du receveur serveur dans ce build. Il n'y a aucun contournement depuis
la config.

**Cause 3 — tu as posé `sanctuaryOverflowFix` en attendant un effet.** Elle n'en a pas.
Elle est lue, affichée dans la ligne de log, et lue par rien ;
`SanctuaryDistanceMixin` s'applique inconditionnellement. Voir
[configuration](configuration.fr.md#sanctuaryoverflowfix).

## Symptôme : `/stopbattle` est refusée ou absente

| Ce que tu vois | Cause | Remède |
|---|---|---|
| `No player was found` | tu l'as exécutée depuis la console ou un bloc de commande ; le gestionnaire appelle `getPlayerOrThrow()` en premier | exécute-la en tant que joueur |
| `Unknown command` | le module de l'API de commandes de Fabric manque, donc `CommandRegistrationCallback` ne s'est jamais déclenché — mais alors le mod ne se serait pas chargé du tout | installe l'API Fabric complète |
| `Incorrect argument for command` sur `/stopbattle <player>` | tu es sous le niveau de permission 2 ; le nœud d'argument est caché de ton arbre de commandes | c'est le comportement voulu, voir [commandes](commands.fr.md#stopbattle-player--restreinte-pas-ajoutée) |
| `Battle stop requested.` mais le combat continue | le `/stopbattle <name>` interne n'a trouvé aucun combat, ou Battle Tower n'est pas installé | le message est envoyé avant que la commande interne tourne et n'est pas conditionné à son résultat |

⚠️ Un cas à surveiller : la ligne de log
`[CheeseCompat] Could not restrict /stopbattle <player>: <message>` signifie que la
réflexion dans les maps privées `children` et `arguments` de Brigadier a échoué. Le reste du
mod va bien, mais **`/stopbattle <player>` est ouverte à tous les joueurs**, qui peuvent
alors mettre fin au combat de n'importe qui par son nom.

## Symptôme : `Couldn't parse element ... cobblesafari_portal_frame` au démarrage

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

**Cause.** Le bloc cadre de portail n'est enregistré que si CobbleSafari est présent, mais
sa table de butin est livrée inconditionnellement. Sans CobbleSafari, la table référence un
bloc qui n'existe pas.

**Remède.** Aucun n'est nécessaire. Le serveur démarre normalement ; c'est une table de
butin orpheline. Vérifié sur un serveur de test à 475 mods. C'est la ligne la plus signalée à
propos de ce mod et elle n'a jamais rien voulu dire.

## Symptôme : les pâturages Daycare+ produisent un œuf puis s'arrêtent

**Cause.** C'est le défaut de ce mod, et c'est l'inverse de celui de Daycare+. Un pâturage
sans état stocké prend `simpleMode = true`, `simpleArmed = true` dans
`DaycareSimpleModeStore` — un œuf, puis désarmement.

**Remède.** Clique l'horloge dans l'**emplacement 5** de la GUI du pâturage pour le passer
en « Reproduction automatique ». C'est par pâturage ; le réglage est indexé par dimension et
position de bloc et stocké dans `config/cheesecompat_daycare.json`. Si tu veux que chaque
pâturage soit en automatique par défaut, le défaut est dans `DaycareSimpleModeStore` et
demande une reconstruction. Comportement complet dans
[le catalogue Daycare+](fixes-daycare.fr.md#reproduction-ponctuelle).

## Symptôme : les IV de Daycare+ ne sont pas ceux que Daycare+ documente

**Cause.** Quatre mixins réécrivent les règles d'IV exprès. Sans objet tenu, les six IV sont
la moyenne entière de ceux des parents, pas de l'aléatoire. Avec un Destiny Knot, cinq sont
hérités et un est moyenné. Le mode compétitif ne surcharge plus la logique des objets.

**Remède.** Rien à corriger — c'est intentionnel. Le tableau complet est dans
[le catalogue Daycare+](fixes-daycare.fr.md#3-repli-aléatoire-remplacé-par-la-moyenne-des-parents).
Si tu veux le comportement propre de Daycare+, ce mod n'est pas le bon mod à installer.

Note aussi la fertilité : un œuf hérite de `(moyenne des parents) - 1`, donc une lignée
décline d'un point par génération, et un Pokémon à fertilité nulle ne peut jamais se
reproduire en mode compétitif, quel que soit
`allow_breeding_without_fertility`.

## Symptôme : le PNJ Battle Tower apparaît dans un mur, ou les joueurs au mauvais endroit

**Cause.** `BattleArenaPositionMixin` réécrit les offsets de l'arène à `0` pour le joueur et
`18` pour le PNJ. `18` est l'écartement des plateformes d'**une construction d'arène
précise** sur un serveur. Avec toute autre géométrie, le PNJ atterrit là où 18 blocs depuis
ton ancre tombent.

**Remède.** Il n'y a pas de config pour ça. Soit tu construis ton arène avec 18 blocs entre
les plateformes, soit tu changes la valeur de retour du `@ModifyConstant` dans
`BattleArenaPositionMixin` et tu reconstruis. Voir
[les offsets de téléportation de l'arène](fixes-battle-tower.fr.md#8-offsets-de-téléportation-de-larène).

## Symptôme : le PNJ Battle Tower a une barre de vie mais aucun Pokémon visible

**Cause.** Le PNJ a fini dans un chunk différent de l'ancre de l'arène, et Battle Tower ne
force-load que le chunk de l'ancre, donc le paquet d'apparition a été jeté par le traqueur
d'entités.

**Remède.** `BattleTowerChunkPreloadMixin` gère déjà ça en force-loadant un carré de 3×3
chunks autour du milieu. Si le symptôme persiste, cherche sa ligne `ERROR` — le préchargement
est entouré d'un catch pour qu'un échec ne casse pas le combat :

```bash
grep -i "preload" logs/latest.log
```

## Symptôme : l'équipe d'un joueur est revenue fausse de la Battle Tower

Le pire cas, et celui sur lequel agir vite.

**Cause.** L'une des trois parties coopérantes de la chaîne de restauration n'a pas abouti :
la sauvegarde au verrouillage (`TeamFullRestoreMixin`), la restauration à la reconnexion
(`BattleTowerDisconnectFix`), ou la garde qui bloque un nouveau combat pendant une
restauration en cours (`BattleTowerManagerMixin`).

**Remède.** Avant toute chose, vérifie si la sauvegarde existe encore :

```bash
ls config/cheesecompat_bt_backups/
```

Un `<uuid>.dat` présent signifie que la restauration n'a jamais abouti et que la vraie équipe
du joueur est **encore dans ce fichier**. Ne le supprime pas. La restauration réessaie 60
fois à raison d'une par seconde à partir de sa connexion, et ne se déclenche que lorsque tous
les UUID de la sauvegarde sont présents dans l'équipe vivante — donc un chargement d'équipe
Cobblemon lent peut la pousser hors de la fenêtre.

Fais reconnecter le joueur et surveille le log. S'il revient et court immédiatement à la
tour, la garde `cheesecompat.bt.restore_pending` doit l'arrêter :

```text
Restauration de l'équipe de la Tour de Combat en cours — patientez quelques secondes avant de lancer un nouveau combat.
```

Si une équipe est revenue avec les **niveaux forcés** de la tour comme vrais niveaux, la
restauration a tourné contre une équipe encore en reconstruction. Détails dans
[le catalogue Battle Tower](fixes-battle-tower.fr.md#7-déconnexion-et-reconnexion-en-pleine-partie).

## Symptôme : tous les combats du serveur se figent

**Cause, si tu as réactivé `RctForcedSwitchFix` :** c'est ce qu'il fait. Il a été désactivé
en 1.2.52 après avoir fait exactement ça en production. Son prédicat de combat figé marque
l'état normal de chaque tour pendant qu'un joueur décide, et trois secondes de réflexion
suffisent à le déclencher.

**Remède.** Assure-toi que l'enregistrement dans `CheeseCompatMod.onInitialize()` est
toujours commenté :

```bash
grep -n "RctForcedSwitchFix" src/main/java/com/cheesecompat/CheeseCompatMod.java
```

```text
71:        //     RctForcedSwitchFix.register();
```

Le `//` est le correctif. L'autopsie complète, et ce qu'une version correcte de ce chien de
garde devrait faire différemment, sont dans
[la page architecture](architecture.fr.md#-le-chien-de-garde--la-cicatrice-à-lire).

**Si tu ne l'as pas réactivé**, ce mod n'est pas ta cause — regarde ce qui d'autre touche la
boucle de combat de Cobblemon.

## Symptôme : les objets tenus par les Pokémon ne s'échangent plus, à l'échelle du serveur

**Cause.** Un mixin sur la gestion des objets tenus de MegaShowdown. Ce mod n'en contient pas
— il a été tenté en 1.2.37, a cassé tous les échanges d'objets tenus pour tout le monde, a
été annulé, et n'est délibérément pas retenté. Si tu vois ça, autre chose sur ton serveur le
fait, ou quelqu'un a remis ce mixin.

**Remède.** Emploie l'API publique `CobblemonHeldItemManager.registerStackRemap` de
Cobblemon à la place, comme le fait `CacademyMegaStoneRemapPatch`. Voir
[l'approche refusée](fixes-cobblemon.fr.md#-2-le-mixin-sur-les-objets-tenus-refusé-jamais-retenté).

## Symptôme : un Pokémon alpha ne peut pas être capturé

**Cause.** Si `AlphaScaleHitboxFixMixin` ne s'applique pas, le bug sous-jacent de
CobblemonAlphas est de retour : les balles rebondissent sans animation, sans message et sans
rien dans le log.

**Remède.** Le mixin se déclenche au premier tick d'une entité et seulement pour les entités
marquées `IS_ALPHA: true` dans `Pokemon.PersistentData`. Un alpha qui a déjà tické une fois
avant le chargement du mod ne sera pas retraité. L'équivalent manuel, que le correctif
automatise, est un aller-retour NBT :

```mcfunction
/data merge entity <cible> {Pokemon:{ScaleModifier:2.5f}}
```

avec la **même** valeur qu'il a déjà, ne changeant rien. Cela seul rend l'alpha capturable.
Personne n'a identifié quel état est réellement corrompu. Voir
[le catalogue Cobblemon](fixes-cobblemon.fr.md#6-les-alphas-sont-incapturables-en-silence).

## Symptôme : les packs de ressources client ne s'activent pas automatiquement

**Cause.** `CheeseCompatClient` n'agit que si `file/CacademyMons` est **absent** de la
liste des packs activés du client. Si cette entrée est présente — même venue d'un
`options.txt` périmé — l'activateur rend la main immédiatement et ne change rien d'autre.

**Remède.** Cherche dans le log client la ligne qu'il affiche quand il tourne vraiment :

```text
[CheeseCompat] Arkensia resource packs not configured — activating...
```

Si cette ligne manque, la garde a court-circuité ; retire `file/CacademyMons` de
`options.txt` et redémarre le client. Si elle est présente mais que les packs restent
éteints, cherche l'avertissement qui nomme ceux qu'il n'a pas trouvés :

```text
[CheeseCompat] N packs not found: [...]
```

Un pack absent de `resourcepacks/` ne peut pas être activé. La liste elle-même vient de
`config/resourcepackoverrides.json`, synchronisé par AutoModpack, avec un repli codé en
dur — voir
[le catalogue « tout le reste »](fixes-misc.fr.md#lactivateur-de-packs-de-ressources-client).

## Symptôme : `CheeseCompatModsFix/fabric.mod.json` indique la version 1.1.0

**Cause.** Tu lis le mauvais fichier. Il y en a deux : celui qui est empaqueté est
`src/main/resources/fabric.mod.json`, qui déclare `"version": "${version}"` et ressort en
`1.2.53+1.21.1` après `processResources`. La copie de 27 lignes à la **racine du mod** n'est
lue par rien, empaquetée nulle part, et porte encore `"version": "1.1.0"` et une description
limitée à « Battle Tower, Legendary Monuments, Arc Phone toggles ».

**Remède.** Rien n'est cassé — Fabric et la liste des mods en jeu rapportent la bonne
version. Le fichier à la racine est un résidu d'une ancienne structure de projet et devrait
être supprimé pour que personne d'autre ne le lise en premier. Sa description périmée est la
moitié la plus trompeuse : elle sous-estime le périmètre du mod d'une douzaine de mods
visés.

---

## Produire un rapport de bug utile

Comme le mode d'échec normal est le silence, un rapport qui dit seulement « le correctif X ne
marche pas » n'est pas exploitable. Quatre choses en font un qui l'est.

**1. Les versions exactes.** Pas « la dernière » — la chaîne de version de ce mod, celle de
Minecraft, celle de Fabric Loader, et celle du **mod visé par le correctif**. Ce dernier
champ est le plus utile de tous, parce que l'explication la plus probable pour un correctif
qui a cessé de marcher est que le mod visé a été mis à jour.

**2. Les lignes de démarrage.** Depuis le log du serveur :

```bash
grep CheeseCompat logs/latest.log
```

Inclus toute la sortie, même si elle fait une ligne. L'*absence* de la ligne d'activation de
la famille est elle-même la réponse dans bien des cas.

**3. Le bloc de résolution, si le mod ne s'est pas chargé.** Fabric en affiche la raison sur
le premier écran du log, avant l'initialisation de tout mod. Copie depuis la première
`Exception` jusqu'à la fin de la stack trace.

**4. Un rapport de crash, s'il y en a eu un.**
`crash-reports/crash-<timestamp>-server.txt`, le fichier entier. Les échecs de mixin y
apparaissent avec le nom de la classe de mixin dans la pile, ce qui désigne directement le
correctif.

Ensuite : [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).

⚠️ Sois réaliste sur ce qu'il faut en attendre. Ce mod corrige les entrailles du code
d'autres auteurs contre des versions précises, pour un seul modpack, et **aucun support
n'est offert pour lui**. Un rapport disant qu'un correctif a cassé après la mise à jour d'un
mod visé décrit un comportement attendu, pas un bug. La réponse honnête sera souvent
« enlève le jar ». Les rapports qui valent la peine d'être ouverts sont ceux où un correctif
est faux *contre la version qu'il prétend viser* — ceux-là sont réels, et corrigeables.

---

← [Architecture](architecture.fr.md) · [CheeseCompatModsFix](../README.fr.md) →
