[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

## Le serveur refuse de démarrer après l'ajout du jar

**Cause.** CheeseDash est une dépendance **obligatoire**. `fabric.mod.json` liste
`"cheesedash": "*"` sous `depends` : Fabric Loader interrompt donc le démarrage quand il
est absent, avec un message nommant `eggandcheese` et le `cheesedash` manquant.

**Remède.** Mets le jar de CheeseDash dans le même dossier `mods/`. Compiler EggAndCheese
depuis les sources exige aussi que CheeseDash soit construit d'abord, parce que la
dépendance Gradle est un glob `fileTree` sur `../CheeseDash/build/libs`.

Les trois autres entrées de `depends` — `fabricloader >=0.16.0`, `fabric-api`,
`minecraft ~1.21.1` — échouent de la même façon, aussi explicitement.

## Le mod est dans `mods/` mais rien ne se passe

**Cause.** Il ne s'est pas initialisé.

**Remède.** Cherche dans le log les deux lignes que le mod affiche toujours :

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

Les deux présentes signifient que tous les registres, le critère, les payloads, le handler
de réconciliation et les deux commandes sont en place. `Initializing...` sans `Ready!`
signifie une exception pendant l'init — la trace d'appel est juste en dessous.

Aucune des deux signifie que le jar n'a pas été chargé du tout : mauvais dossier, un
`-sources.jar` ou `-dev.jar` par erreur, ou un jar construit pour une autre version de
Minecraft.

## `/eac` n'est pas reconnue

**Cause.** L'une de trois choses.

- Tu es la **console du serveur**. Tout l'arbre `/eac` exige `isExecutedByPlayer()` : pour
  la console, la commande n'existe pas.
- Tu n'es pas admin. `PermissionUtil.isAdmin` vérifie `eggandcheese.admin` via
  fabric-permissions-api, avec repli sur op niveau 2. Brigadier masque une commande dont le
  `requires` échoue au lieu de la refuser, et c'est pourquoi tu obtiens le
  `Unknown or incomplete command` de Minecraft plutôt qu'une erreur de permission.
- Le mod n'a pas chargé — voir plus haut.

**Remède.** Lance-la en tant que joueur avec op niveau 2, ou accorde `eggandcheese.admin`
dans LuckPerms. Noter que `/pedestal` n'est pas concernée : elle n'a aucune exigence de
permission, donc si `/pedestal` marche et `/eac` non, ton problème est une permission, pas
un chargement.

## `/eac give <id>` répond `ID inconnu`

**Cause.** L'id n'existe pas dans le registre, exactement tel que tapé. La recherche est
une correspondance exacte, sensible à la casse, et l'argument n'a **aucune complétion** :
la faute de frappe est donc l'échec normal.

**Remède.** Lis l'id dans la liste Collectibles du dashboard et copie-le. Vérifie la casse.
`/eac reload` affiche combien d'entrées le serveur possède, ce qui t'indique au moins si le
registre est peuplé.

## Un changement de configuration n'a aucun effet

**Cause.** Presque toujours l'un de ces cas.

- Tu as créé `config/eggandcheese.json`. **Il n'y a pas de fichier de configuration.** Le
  mod n'appelle jamais `getConfigDir()`. Un fichier créé là est ignoré.
- Tu as édité `world/data/eggandcheese_registry.dat` à la main. Minecraft tient cet état en
  mémoire et écrase le fichier à sa prochaine sauvegarde : ton édition est perdue.
- Tu as changé `ftbAutoSync`. C'est un réglage mort — stocké, renvoyé par l'API, lu par
  personne.
- Tu as changé `textureRenderDistance` et tu l'attendais sur un client déjà connecté avant
  le changement. La valeur est poussée au moment du `PUT`, aux joueurs connectés à cet
  instant.
- Tu as réglé la distance de rendu globale à `0` dans l'onglet **Configuration** du
  dashboard. Son formulaire transforme `0` en `10` avant de l'envoyer ; passe par l'API pour
  `0`.

**Remède.** Change les réglages via `PUT /api/v1/eggs/config` ou le dashboard, et relis la
réponse — elle renvoie l'état stocké.

## Les œufs se dégradent en boîtes trop près du joueur

**Cause.** `textureRenderDistance`, et le fait qu'il n'ait peut-être jamais atteint le
client. `sendRegistrySync` est appelé à un seul endroit — `PUT /config` — donc un client
connecté après le dernier changement de configuration utilise encore son défaut compilé de
20.

**Remède.** Seuls les joueurs connectés au moment d'un enregistrement de la configuration
reçoivent la valeur : un enregistrement « après chaque redémarrage » ne suffit donc pas.
Réenregistre une fois les joueurs qui comptent connectés, et à nouveau après chaque
reconnexion. Pour les œufs cachés qui doivent toujours être en détail complet, mets plutôt
un `renderDistanceOverride` de `0` — `0` signifie « ne jamais dégrader », `-1` « suivre la
valeur globale ». Cette surcharge n'existe que sur les œufs de chasse ; les œufs décoratifs
et ceux posés sur une stèle suivent toujours la valeur globale.

## Le dashboard n'a pas de section Œufs

**Cause.** CheeseDash découvre les modules par l'entrypoint `cheesedash` au démarrage.
Pas de section signifie que l'entrypoint n'a jamais été chargé.

**Remède.** Dans l'ordre :

1. Vérifie que les deux jars sont dans `mods/` et que le serveur a démarré proprement.
2. Vérifie que `[EggAndCheese] Ready!` est dans le log.
3. Appelle `GET /api/v1/modules` et cherche le préfixe `eggs`. Présent là mais absent de la
   barre latérale est un problème côté dashboard ; absent des deux est un problème de
   chargement.
4. Vérifie une incompatibilité de version avec CheeseDash. EggAndCheese compile contre les
   `DashboardModule`, `WebDashboard` et `DashboardRegistry` de CheeseDash ; si ceux-ci ont
   changé de façon incompatible, la construction de l'entrypoint lève une exception et
   CheeseDash la journalise.

## Toutes les routes eggs renvoient 503

**Cause.** `EggAndCheeseDashboardModule` répond
`503 {"error":"Server not available"}` pendant que sa référence au `MinecraftServer` est
nulle — c'est-à-dire avant `onServerStart` ou après `onServerStop`.

**Remède.** Attends la fin du démarrage. Un 503 persistant après le démarrage signifie
qu'`onServerStart` n'a jamais été appelé, ce qui désigne le cycle de vie des modules de
CheeseDash plutôt que ce mod.

## Un joueur fait un clic droit sur un œuf et rien ne se passe

**Cause.** Quatre possibilités, par ordre de probabilité.

- **Le bloc n'a pas de `registryId`.** `handlePlayerDiscovery` sort à sa première
  vérification. C'est l'apparence d'un bloc non lié ou délié par réconciliation — un œuf
  qui s'affiche et ne fait rien.
- **L'id ne désigne aucune entrée du registre.** Même sortie silencieuse. C'est un
  orphelin.
- **Le joueur l'a déjà trouvé.** Il reçoit `Vous avez déjà trouvé celui-ci !` en barre
  d'action, facile à manquer au-dessus de la barre d'action.
- **Le joueur est admin.** Un clic droit d'admin prend une branche complètement
  différente : il reçoit une copie décorative, et aucune découverte n'est enregistrée.

**Remède.** Lance le bouton **Intégrité** du dashboard avec la zone chargée — il signale
les orphelins par coordonnées. Pour le cas admin, teste avec un compte sans op ; voir
[placement et découvertes](placement-and-discovery.fr.md#le-clic-droit-dun-admin-nest-pas-une-découverte).

## Deux œufs identiques partagent un id

**Cause.** Un doublon : un `registryId` revendiqué par plus d'un bloc. Copier une
construction à la WorldEdit, coller deux fois une schématique ou restaurer une partie de
carte produisent tous cela.

**Remède.** Charge la zone, appuie sur **Intégrité**, lis le rapport, confirme la
réparation. La réparation *délie* les blocs en surplus — elle met leur id à vide — elle ne
les supprime jamais. Procédure complète dans
[intégrité](integrity.fr.md#comment-sen-servir-vraiment-sur-une-carte-qui-a-dérivé).

⚠️ L'outil ne voit que les **chunks chargés**. Un doublon dans un chunk que personne n'a
visité depuis le redémarrage est introuvable. Parcours la zone d'abord.

## Un œuf est dans le dashboard mais il n'y a aucun bloc à ses coordonnées

**Cause.** Quatre façons d'arriver là.

- L'entrée a été créée par `POST /collectibles`, qui crée une entrée de registre et **ne
  pose aucun bloc**.
- Un admin a cassé le bloc. Cela met `positionLost = true` et la position à
  `BlockPos.ORIGIN`, et le signale à l'admin en doré.
- Un non-admin en mode créatif l'a cassé, ou une explosion l'a détruit. Rien ne
  l'empêche, et rien ne met l'entrée à jour : elle garde ses coordonnées et
  `positionLost = false` — voir
  [placement et découvertes](placement-and-discovery.fr.md#indestructibilité).
- L'entrée a été renommée. `POST /rename` change la clé du registre et laisse le bloc
  porter l'ancien id : tu obtiens une entrée sans bloc et un bloc orphelin. C'est un bug,
  pas un problème de configuration.

**Remède.** Donne-toi l'item admin de cet id (`/eac give <id>` ou le bouton give-admin du
dashboard) et pose-le là où l'œuf doit être. Poser un item qui désigne un id existant relie
l'entrée et efface `positionLost`.

## L'œuf affiche `egg_014` au lieu de son titre

**Cause.** Par conception, même si ce n'était probablement pas l'intention. Le message de
découverte est `Text.translatable("eggandcheese.found", registryId)` — il passe l'**id**.
`title` ne sert qu'à la quête FTB.

**Remède.** Nomme tes ids comme des chaînes destinées aux joueurs : `cave_nord`,
`spawn_secret`. Renommer un œuf existant fonctionne, mais lis d'abord la réserve sur le
renommage ci-dessus.

## Les quêtes FTB ne sont pas créées

**Cause.** Plusieurs conditions, toutes journalisées.

**Remède.** Regarde dans le log de démarrage le diagnostic du hook lui-même :

```text
[EAC-FTB] FTB Quests not present — hook disabled
```

signifie que FTB Quests n'est pas installé. Sinon, cherche

```text
[EAC-FTB] FTB Quests hook initialized — Java API ready
```

Son absence, sans la ligne « not present », signifie que la réflexion n'a pas réussi à
résoudre quelque chose de requis — la ligne de log juste avant nomme quoi. C'est le mode de
défaillance attendu après une mise à jour de FTB Quests, et c'est pourquoi
[la page de l'intégration](ftb-quests.fr.md) recommande la voie de l'advancement comme
repli.

Si le hook s'est initialisé et que les quêtes n'apparaissent toujours pas, vérifie
`ftbQuestsEnabled` dans `GET /api/v1/eggs/config`. Il vaut `false` par défaut.

## Les joueurs cochent les quêtes d'œufs sans trouver l'œuf

**Cause.** La classe `CustomTask` n'a pas été résolue, et le hook est retombé sur
`CheckmarkTask`, qui est une case à cocher manuelle.

**Remède.** Aucun dans ce mod — il utilise `CustomTask` dès que FTB Quests le fournit. Le
log dit laquelle tu as obtenue :

```text
[EAC-FTB] CustomTask not found — falling back to CheckmarkTask (players can click)
```

Soit tu mets FTB Quests à jour vers une version qui a `CustomTask`, soit tu pilotes tes
quêtes depuis un advancement à toi sur le déclencheur `eggandcheese:egg_found` (voir
[l'advancement](display.fr.md#ladvancement)) et tu rends la tâche non cliquable
toi-même.

## Les coches de quête ont disparu après une synchronisation

**Cause.** `POST /config/sync-ftb` supprime chaque quête liée et la recrée avec un nouvel
id. La progression des joueurs est attachée aux anciens ids et ne survit pas.

**Remède.** Rien à récupérer côté FTB. Les enregistrements `discoveredBy` du mod sont
intacts dans le registre des œufs : tu peux voir qui avait trouvé quoi via
`GET /collectibles/:id/discoveries`. Réserve la synchronisation complète à la mise en place
et aux fichiers de quêtes sérieusement dérivés, pas à l'entretien de routine.

## Le serveur s'est figé, puis le watchdog l'a tué

**Cause.** Si cela arrive autour d'un chargement de chunks et que la trace d'appel
mentionne `BLOCK_ENTITY_LOAD`, `AdminEggPlacedEvent`, `getBlockEntity` ou
`getChunkBlocking`, c'est le schéma de blocage de juin 2026 : du travail fait dans un
handler de chargement de block entity qui force le chargement d'un chunk, réentrant dans
le pipeline de chunks de c2me depuis l'intérieur.

**Remède.** Vérifie que les deux défenses sont encore dans le code que tu fais tourner :

1. `AdminEggPlacedEvent.register()` ne fait qu'*empiler* sur `BLOCK_ENTITY_LOAD` et vide la
   file dans `END_SERVER_TICK`. Cela ne doit pas être un `server.execute`, qui s'exécute en
   ligne sur le thread serveur et bloque à l'identique.
2. `unbindAt` appelle `world.getChunkManager().isChunkLoaded(cx, cz)` avant de toucher
   `getBlockEntity`, et renvoie `false` quand le chunk n'est pas chargé.

Si un correctif a retiré l'un des deux, remets-le. Le récit complet est dans
[intégrité](integrity.fr.md#ne-jamais-forcer-le-chargement-dun-chunk-depuis-un-handler-de-chargement-de-block-entity).

## Un à-coup en arrivant en vol dans un champ d'œufs

**Cause.** La génération de texture est synchrone sur le thread de rendu à la première
apparition de chaque ADN distinct. Cinquante œufs distincts, c'est cinquante textures
64×64 plus deux images émissives chacune, générées à vue.

**Remède.** Comportement attendu, pas un bug, et cela n'arrive qu'une fois par ADN et par
session. Baisse `textureRenderDistance` pour que les œufs lointains utilisent la boîte. Les
œufs qui partagent un ADN partagent une texture : un mur décoratif d'œufs identiques est
donc quasi gratuit.

## Un bloc incassable reste là où était une stèle

**Cause.** `eggandcheese:pedestal_extension`. Une stèle haute en pose au-dessus d'elle
pour porter la collision de la plateforme, et ils sont enregistrés avec une résistance de
type bedrock. Retirer la stèle par WorldEdit ou un rollback — tout ce qui contourne les
mises à jour de blocs — contourne `removeAllExtensions()`. La casser, `/setblock` et les
explosions, non.

**Remède.** `/setblock <x> <y> <z> air` sur chaque bloc restant. Il n'existe aucun moyen de
les casser en jeu.

## Les clients anglais voient des mots français dans les infobulles

**Cause.** `getDisplayName()` sur `PatternType`, `PaletteType`, `RarityType` et les autres
renvoie des chaînes françaises codées dans le source Java. L'infobulle d'item et les champs
`displayName` du dashboard les utilisent toutes les deux.

**Remède.** Aucun sans modification du code. Les noms de constantes d'enum (`CRYSTALS`,
`ICE`, `EPIC`) sont neutres linguistiquement et sont ce que `GET /enums` renvoie en `id` :
un consommateur qui veut ses propres traductions doit s'appuyer sur ceux-là.

## Un `PUT` a renvoyé 200 mais rien n'a changé

**Cause.** `EggsApi.parseJson` avale le JSON mal formé et renvoie un objet vide : une
requête au corps cassé se comporte donc exactement comme `{}` — un succès sans effet. Le
bornage et les replis d'enum font la même chose plus discrètement : `patternScale: 99`
devient `4`, et `pattern: "SPOT"` conserve l'ancienne valeur. Enfin, une `scale` envoyée
avec `x`, `y`, `z` ou `dimension` — comme le fait l'onglet Localisation du dashboard — est
stockée dans le registre mais n'atteint jamais le bloc posé ; envoie-la seule
([détails](dashboard.fr.md#put-collectiblesid)).

**Remède.** Lis toujours le corps de la réponse, qui est l'état complet nouveau, et
compare-le à ce que tu as envoyé. Vérifie ton en-tête
`Content-Type: application/json` et ta syntaxe JSON.

## Un compte READ_ONLY du dashboard a supprimé un œuf

**Cause.** Ce n'était pas une session `READ_ONLY`. `EggsApi` ne fait aucun contrôle de
rôle lui-même, mais le dispatcher de modules de CheeseDash rejette, par un `403`, toute
requête non-`GET` sous `/api/v1/eggs/` qui échoue à `requireWrite`, avant que le module ne
la voie : un vrai compte `READ_ONLY` ne peut donc rien supprimer, renommer, donner ni
reconfigurer. La requête venait d'un compte `OPERATOR` ou `SUPER_ADMIN`, ou du jeton
maître, qui est `SUPER_ADMIN`. Le journal d'audit de CheeseDash enregistre chaque requête
non-`GET` adressée à un module, avec sa session.

**Remède.** Consulte le journal d'audit et le rôle du compte. Comme `OPERATOR` est la seule
barrière de ce module, tout `OPERATOR` peut supprimer des œufs : ne confie ce rôle à
personne à qui tu ne confierais pas les œufs — voir
[l'API du dashboard](dashboard.fr.md#ce-que-le-cœur-applique-réellement). Restreindre le
port du dashboard par pare-feu ou par tunnel SSH reste la parade habituelle.

## Signaler un bug

Ouvre un ticket sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **Les versions des jars.** `eggandcheese-1.0.0+1.21.1.jar` et le nom de fichier exact de
   ton jar CheeseDash.
2. **Les lignes de démarrage du mod** depuis `logs/latest.log` :
   - `[EggAndCheese] Initializing...` et `[EggAndCheese] Ready!`
   - toutes les lignes `[EAC-FTB]` du démarrage, si le problème concerne les quêtes
3. **La trace d'appel**, s'il y en a une. Cherche `eggandcheese` dans le log et inclus tout
   le bloc, pas seulement la première ligne.
4. **Ce que dit le dashboard.** `GET /api/v1/eggs/config`,
   `GET /api/v1/eggs/stats`, et pour un problème propre à un œuf,
   `GET /api/v1/eggs/collectibles/<id>` et `GET /api/v1/eggs/integrity`. Ces quatre
   réponses décrivent l'état bien mieux qu'une capture d'écran.
5. **L'id et les coordonnées de l'œuf**, et dans quelle dimension.
6. **Si c2me ou un autre mod de système de chunks est installé**, pour tout ce qui touche à
   un gel, à un kill du watchdog ou au chargement de chunks.

Pour un problème de rendu côté client, c'est le `logs/latest.log` du client qui compte, et
précise si l'œuf s'affiche comme une boîte (question de niveau de détail) ou pas du tout
(question de renderer).

---

← [Architecture](architecture.fr.md) · [Installation](installation.fr.md) →
