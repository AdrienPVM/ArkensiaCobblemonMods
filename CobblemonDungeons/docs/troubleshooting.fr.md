[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Le symptôme d'abord, puis la cause, puis le remède. Le log du serveur est
`logs/latest.log` ; presque tout ce qui suit s'y diagnostique.

---

## Le serveur refuse de démarrer en nommant un mod manquant

**Cause.** Cobblemon, RCTApi, Fabric API et CheeseDash sont tous les quatre dans `depends` :
Fabric abandonne plutôt que de charger le mod sans eux. Le message nomme l'identifiant
qu'il voulait : `cobblemon`, `rctapi`, `fabric-api` ou `cheesedash`.

**Remède.** Installe le mod nommé à une version satisfaisant `fabric.mod.json` : Cobblemon
`>=1.6.1`, RCTApi `>=0.13.7-beta`. Pour `cheesedash`, construis d'abord le mod frère et
place son jar dans `mods/` — voir [l'installation](installation.fr.md).

## La construction échoue avant même de compiler

**Cause.** CobblemonDungeons résout CheeseDash par un glob sur
`../CheeseDash/build/libs/`. Un CheeseDash non construit donne une arborescence vide et une
dépendance impossible à résoudre.

**Remède.**

```bash
cd CheeseDash && ./gradlew build
cd ../CobblemonDungeons && ./gradlew build
```

Note que le script de build de ce mod est `build.gradle.kts`, pas `build.gradle`, et que sa
version est dans ce fichier plutôt que dans `gradle.properties`. L'artefact est
`build/libs/cobblemon-dungeons-1.1.147+1.21.1.jar`.

## `./gradlew build` ne trouve pas une classe Velocity

**Cause.** Tu construis le mauvais projet. `velocity-party-forwarder/` est un plugin
Velocity distinct et ne fait pas partie de la construction principale — le
`settings.gradle.kts` racine ne l'inclut pas. Si tu as lancé Gradle dans ce dossier, il lui
faut un jar de l'API Velocity que tu fournis toi-même.

**Remède.** Pour le mod, construis depuis `CobblemonDungeons/`. Pour le plugin, place
d'abord `velocity-proxy.jar` dans `velocity-party-forwarder/libs/`. Le jar du plugin va dans
le `plugins/` de ton proxy, jamais dans `mods/`.

## `/dungeons` n'existe pas

**Cause.** Le mod n'est pas sur le serveur, ou il n'a pas réussi à se charger.

**Remède.** Cherche `Initializing Cobblemon Dungeons...` dans le log. S'il est absent, le
jar n'est pas dans `mods/` ou Fabric l'a rejeté. S'il est présent et suivi d'une erreur de
mixin, le mod a planté pendant son initialisation — les trois mixins sont `required: true`,
donc une injection ratée est fatale, et cela signifie généralement une version de Minecraft
autre que 1.21.1.

## *Cette commande n'est disponible que sur le serveur principal.*

**Cause.** Le rôle de ce serveur est `dungeon`. `/dungeons` et `/dungeon` sans
sous-commande y affichent ce message à dessein, et tout l'arbre `/DungeonsParty` y est
masqué (*Commande inconnue ou incomplète*). `/dungeons leave` fonctionne — de même, pour les
opérateurs, que `sql test` et `partydebug`, dont la vérification de rôle est écrasée dans le
source (voir [les commandes](commands.fr.md#dungeons-sql-test)).

**Remède.** Si c'est censé être le serveur principal, retire
`-Dcobblemon.dungeons.serverRole=dungeon` de la ligne de lancement, supprime
`COBBLEMON_DUNGEONS_SERVER_ROLE`, et redémarre. Le rôle est lu une fois puis mis en cache,
donc un redémarrage est obligatoire. La ligne de log qui le confirme est
`Cobblemon Dungeons server role: main`.

## *Vous avez besoin d'un ou plusieurs pokémon dans votre équipe pour entrer dans les donjons*

**Cause.** `DungeonsPartyManager.hasPokemon` a trouvé une équipe Cobblemon vide. Chaque
point d'entrée la vérifie — la commande, la création de groupe, les invitations et les
jonctions.

**Remède.** Avoir au moins un Pokémon dans l'équipe, pas dans le PC. Si un joueur affirme
que la sienne n'est pas vide, la même méthode renvoie `false` sur toute exception du
stockage Cobblemon : vérifie le log pour une erreur Cobblemon à cet instant.

## Mon changement de configuration ne fait rien

**Cause.** Les fichiers ne se rechargent pas de la même manière, et deux d'entre eux ne se
rechargent jamais.

**Remède.** Compare ton fichier à ce tableau :

| Fichier modifié | Ce qu'il faut |
|---|---|
| `dungeons/*.json` | `POST /api/v1/dungeons/configs/reload` |
| `dungeon_tickets.json` | Le même rechargement, plus une reconnexion du client pour l'interface |
| `trainers/*.json` | **Un redémarrage du serveur.** `TrainerLoader.load` ne tourne que sur `SERVER_STARTED` |
| `config.json` | **Un redémarrage du serveur** pour le pool de base de données |
| Rôle du serveur | **Un redémarrage du serveur** |

Et un point de plus : un groupe déjà dans un donjon conserve l'objet de configuration avec
lequel son instance a été construite. Un rechargement ne change jamais les règles d'une
partie en cours.

## Le rechargement réussit mais le donjon est inchangé

**Cause.** Habituellement l'une de trois choses. Le fichier n'est pas dans `dungeons/` ou ne
finit pas par `.json` — le chargeur filtre sur les deux. Ou son `general.id` diffère de ce
que tu attends, puisque l'enregistrement se fait par l'identifiant contenu dans le fichier
et non par le nom du fichier. Ou deux fichiers déclarent le même `general.id`, cas où le
dernier chargé gagne, dans un ordre non spécifié.

**Remède.** Garde un fichier par donjon, nommé `<id>.json`, correspondant à son
`general.id`. Vérifie les lignes `Registered dungeon from file: …` dans le log : il y en a
une par fichier réellement chargé.

## *Les donjons sont pleins ! Veuillez réessayer plus tard.*

**Cause.** Un emplacement de localisation est alloué par partie simultanée — sur ce serveur
en mode `SAME_SERVER`, sur le serveur de donjon en mode `CROSS_SERVER` — et tous sont
occupés. Un donjon sans `additionalLocations` n'a qu'un seul
emplacement : un deuxième groupe ne peut pas entrer.

**Remède.** Ajoute des emplacements dans `additionalLocations`, chacun avec ses propres
coordonnées, et recharge. Sinon, fais échouer de force la partie fantôme depuis le dashboard
avec `POST /api/v1/dungeons/active/fail`. Une partie laissée par un crash n'existe plus
après un redémarrage : si le message persiste malgré un redémarrage, les emplacements sont
réellement occupés.

## *Vous avez besoin d'un Ticket de Donjon valide pour <nom> (Tier N) pour entrer.*

**Cause.** Le chef de groupe n'a aucun ticket dont le `DungeonId` corresponde à
l'identifiant du donjon et dont le `Tier` soit au moins celui demandé. Note que seul le chef
paie.

**Remède.** Survole le ticket : l'infobulle doit montrer une ligne `Donjon :` et une ligne
`Tier :`. Sinon, le composant `custom_data` manque et l'item n'est qu'un sosie. Vérifie que
`DungeonId` est bien le `general.id` du donjon et non son nom affiché — ce sont deux champs
différents. Les tiers sont un plancher : un ticket Tier 5 ouvre une partie Tier 1, pas
l'inverse. Voir [les tickets](tickets.fr.md).

## Un ticket placé dans un pool de butin n'apparaît jamais

**Cause.** Un ticket se référence dans le butin par son **`itemName`**, pas par un
identifiant d'item. Le résolveur essaie d'abord la chaîne comme identifiant d'item, et ne
retombe sur la correspondance par nom de ticket que si cela donne `minecraft:air`.

**Remède.** Utilise l'`itemName` exact de `dungeon_tickets.json`, soit avec `§`, soit avec
`&` à sa place. Les deux formes sont comparées sans tenir compte de la casse. Le log dit
`Unknown loot item ID or ticket name: <chaîne>` quand rien ne correspond — cette ligne
confirme que tu as une faute de frappe.

## Le groupe attend dans le hub et n'est jamais téléporté

**Cause.** `isReadyForPlayers()` exige que les étapes Pieux et Gardiens soient terminées et
qu'aucune tâche d'apparition de dresseur ne soit encore en file. Ces étapes se terminent dès
que le scan des chunks autour de `spawnPos` a été parcouru, **même si moins de spawners que
requis ont été trouvés**, et une apparition de dresseur ratée est retirée de la file — donc
des spawners manquants ou un pool de Gardiens cassé ne retiennent pas un groupe dans le hub.
Ce qui le retient, c'est un monde de donjon introuvable : `tickFastScans` saute l'instance
chaque fois que `locations.spawnPos.dimension` (premier emplacement) désigne une dimension
que le serveur n'a pas. Le groupe attend alors jusqu'à ce que le chronomètre fasse échouer la
partie.

**Remède.** Vérifie que `locations.spawnPos.dimension` est l'identifiant complet d'une
dimension chargée, comme `minecraft:overworld`. Si le groupe est bien téléporté mais qu'un
objectif ne peut jamais être rempli, vérifie, dans cet ordre :

- le nombre de blocs spawner de Gardien dans `guardianSpawnRadius` autour de `spawnPos` est
  au moins `requiredGuardians`, et leurs numéros de variantes figurent dans
  `guardianSpawnerIds` (une liste vide accepte toutes les variantes)
- de même pour les spawners de Pieu et `requiredStakes` — et seul le `stake_spawner` de base
  reçoit jamais un Pieu, voir [la définition d'un donjon](dungeons.fr.md#spawning)
- `spawnerScanVerticalRange` est assez grand pour les atteindre — la valeur par défaut 64
  est une demi-portée autour du Y du spawn
- le pool de Gardiens se résout : une entrée dont le `tier` correspond à celui de la partie,
  ou une entrée avec `tier: 0`
- chaque `trainerId` de ce pool existe, dans le `trainers/` de ce mod (une ligne
  `Registered custom trainer from file: …`) ou dans Radical Trainers ; un échec journalise
  `Trainer template '<id>' not found in any Radical Trainers registry!`

## Les Alphas n'apparaissent jamais

**Cause.** Les Alphas ne font pas partie de la condition de préparation : ils apparaissent
quand un joueur arrive à moins de `alphaTriggerRadius` — 5 blocs par défaut — d'un spawner
d'Alpha que ce donjon est autorisé à utiliser.

**Remède.** Confirme que le numéro de variante du spawner est dans `alphaSpawnerIds`, qu'il
se trouve dans `alphaSpawnRadius` autour de `spawnPos`, et approche-toi vraiment. Vérifie
ensuite `alphaPool` : une espèce inconnue est journalisée une fois puis ignorée, et la
valeur par défaut livrée, `"ghastly"`, est une faute d'orthographe de `gastly` qui ne
produit aucun Alpha.

## Un Gardien ne peut pas être combattu, ou n'a pas d'équipe

**Cause.** Soit l'entité n'a pas été enregistrée auprès de RCTApi, soit son équipe est à
zéro PV depuis un combat précédent, soit le JSON du dresseur a échoué à la conversion au
démarrage.

**Remède.** *Ce dresseur a déjà été vaincu.* signifie que l'équipe est épuisée — un Gardien
ne se combat qu'une fois. Si rien ne se passe du tout, c'est que l'entité ne se résout sur
aucun identifiant de dresseur : cherche `Failed to convert trainer model from file: …` ou
`Failed to load custom trainer file: …` dans le log de démarrage. Rappelle-toi que les
fichiers de dresseurs ne se chargent **qu'au** démarrage du serveur : un dresseur que tu
viens d'ajouter ou de corriger via le dashboard ne fait rien avant un redémarrage.

## Battre le boss ne termine pas la partie

**Cause.** Le chemin de complétion fiable est celui de `DeathHandler`, qui reconnaît le tag
de commande `DungeonBoss` sur l'entité du boss, posé quand le mod le fait apparaître. Un
Pokémon que tu as invoqué toi-même, quel que soit son nom, ne porte pas ce tag.

**Remède.** Laisse le mod invoquer le boss — par le portail, ou en interagissant avec une
entité nommée « boss altar » une fois tous les objectifs complétés. Si le boss n'est jamais
apparu, cherche `Boss species not found: <espèce>` dans le log : une espèce inconnue signifie
pas de boss et une partie impossible à finir. Garde aussi `boss.customName` aligné avec les
`customName` de ton pool ; un second chemin de code, mort celui-là, s'y compare.

## Un joueur déconnecté en pleine partie ne peut pas revenir

**Cause.** C'est voulu, dans certaines limites. `handleDisconnect` retire le joueur et, s'il
était le dernier membre, démonte l'instance. Le retour ne passe que par `partyInstances`,
ce qui exige que le joueur soit dans un groupe dont le chef a encore une instance vivante.

**Remède.** Un joueur solo ne peut pas revenir — il n'y a aucune entrée de groupe pour le
retrouver. Ni un membre d'un groupe dont tous les autres membres sont partis. Dans les deux
cas la partie a disparu et rien n'a été enregistré. Rends-lui son ticket à la main si c'est
ta politique ; le mod n'a aucun mécanisme de compensation.

## Un joueur a perdu tout ce qu'il avait ramassé dans un donjon

**Cause.** C'est le comportement documenté d'une partie `CROSS_SERVER` échouée.
`leaveDungeon` ne sérialise l'état complet du joueur que si `instance.isBossDefeated()` est
vrai. Sur un échec ou une sortie volontaire, seule la position de retour fait le voyage, et
le joueur est averti : *Vous avez quitté ou échoué le donjon. Vos objets et votre
progression n'ont PAS été sauvegardés.*

**Remède.** Rien à corriger : c'est la conception. Annonce-le sur ton serveur avant que
quelqu'un ne le découvre. Les donjons `SAME_SERVER` ne se comportent pas ainsi : le joueur y
est simplement téléporté, donc son inventaire reste ce qu'il était.

## Le chef de groupe reste bloqué sur *Vérification du statut du serveur de donjon...*

**Cause.** Le mode `CROSS_SERVER` envoie une requête BungeeCord de nombre de joueurs au
proxy par la connexion du chef. Sans réponse en 60 ticks, la tentative est abandonnée et,
trois secondes après le premier message, le chef lit *Le serveur de donjon est hors ligne.
Veuillez réessayer plus tard.* — même quand le serveur est démarré. Les raisons habituelles
sont un plugin Velocity absent, ou un nom de `targetServer` qui ne correspond pas à la
configuration du proxy.

**Remède.** Confirme que le client a le mod à la même version. Confirme que
`velocity-party-forwarder` est dans le `plugins/` du proxy et qu'il a bien journalisé ses
deux lignes `Registered channel …` au démarrage du proxy. Confirme que
`networking.targetServer` est exactement le nom que le proxy donne au serveur de donjon.
Pour une installation sur un seul serveur, passe `teleportMode` à `SAME_SERVER` et toute la
question disparaît.

## Les groupes arrivent vides sur le serveur de donjon

**Cause.** L'état du groupe traverse les serveurs par le mécanisme BungeeCord `Forward` et
par le canal personnalisé `cobblemon_dungeons:proxy_forward`, et Velocity ne transmet ni
l'un ni l'autre de lui-même.

**Remède.** Installe le plugin Velocity. Utilise ensuite les diagnostics :
`/dungeons partydebug on`, `/dungeons partydebug status`, `/dungeons partydebug dump`, et
`/dungeons partydebug ping <server>` pour tester l'aller-retour. Le plugin journalise chaque
message transmis en INFO, donc le log du proxy te dit si quelque chose est arrivé.

## *SQL is enabled but the connection failed*

**Cause.** `useSql: true` et HikariCP n'a pas pu construire de pool. L'erreur conservée est
celle rapportée par le pilote : mauvais hôte, mauvais identifiants, schéma absent,
pare-feu.

**Remède.** `/dungeons sql test` affiche l'erreur. Corrige `config.json` et redémarre : rien
ne relit cette section à chaud. Note que le mod livre le mot de passe littéral
`"password"` comme bouchon, donc une installation neuve avec `useSql: true` et une
configuration intacte échouera à l'authentification. SQL n'est nécessaire que pour les
transferts cross-serveur de gros PC — le désactiver est une réponse valable.

## Le module donjons est absent du dashboard

**Cause.** Soit CheeseDash n'a pas découvert l'entrypoint, soit le module n'a pas encore de
serveur.

**Remède.** Un `503` avec `{"error":"CobblemonDungeons not ready"}` signifie que le module
s'est enregistré mais que `onServerStart` n'avait pas tourné : réessaie une fois le serveur
démarré. Aucun module du tout signifie que CheeseDash n'a jamais trouvé l'entrypoint
`cheesedash` : vérifie que les deux jars sont dans `mods/` et que celui que tu as déployé est
bien le jar de ce mod, et non une copie périmée portant une autre version dans son nom.

## Un compte dashboard READ_ONLY modifie des choses

**Cause.** Pas par ce module. `DungeonsApi` n'appelle lui-même jamais `requireWrite` ni
`requireAdmin`, mais le dispatcher de modules de CheeseDash refuse avec un `403` toute
requête non-`GET` d'un rôle inférieur à `OPERATOR` avant que le module ne la voie — voir
[la page dashboard](dashboard.fr.md). Une modification faite par ce compte vient d'ailleurs,
ou le compte n'est pas `READ_ONLY`.

**Remède.** Vérifie le rôle réel du compte dans la liste des comptes de CheeseDash, et
retrouve la requête dans le journal d'audit de CheeseDash. Rappelle-toi que tout `OPERATOR`
peut supprimer un donjon ou écraser un fichier de dresseur : aucune route ici n'exige
`SUPER_ADMIN`.

## Les Alphas ou le boss ont la mauvaise taille

**Cause.** L'échelle est appliquée par l'aspect `scale:<n>` et un flottant `CustomScale`
dans les données persistantes du Pokémon, puis imposée par `PokemonEntityMixin` à chaque
recalcul de dimensions. Le mixin fixe aussi `GENERIC_SCALE` à 1.0 pour que les deux ne se
multiplient pas.

**Remède.** Un boss de taille normale signifie presque toujours que le mixin ne s'applique
pas, ce qui, après une mise à jour de Cobblemon, veut dire que l'API a bougé — cherche
`BattleHandler failed to init (Cobblemon API version mismatch?)` dans le log comme signe que
l'API Cobblemon a glissé sous le mod. Le client a besoin du mod lui aussi : un client vanilla
voit des tailles vanilla.

## Écrire un rapport de bug utile

Joins :

1. **`logs/latest.log`** en entier, issu de la session qui a reproduit le problème. Pas un
   extrait : les lignes de démarrage comptent autant que la défaillance.
2. Les **lignes de version** : le nom de fichier du jar du mod, la version de Fabric
   Loader, et celles de Cobblemon et de RCTApi, toutes présentes dans la liste de mods du
   loader en haut du log.
3. La ligne **`Cobblemon Dungeons server role:`** et, pour un problème cross-serveur, le log
   du proxy aussi.
4. La **configuration du donjon** que tu faisais tourner, en tant que fichier. Ne censure
   rien : elle ne contient aucun secret.
5. Pour un problème de dashboard : la requête, le corps de la réponse et le code HTTP, plus
   le **rôle** du compte utilisé.
6. Ce que tu attendais et ce qui s'est passé, dans cet ordre. « Le groupe n'a jamais été
   téléporté hors du hub » est actionnable ; « les donjons sont cassés » ne l'est pas.

Les lignes de log à chercher avant de déposer quoi que ce soit :

```text
Registered dungeon from file:
Registered custom trainer from file:
Cobblemon Dungeons server role:
Failed to load dungeon file:
Failed to convert trainer model from file:
Boss species not found:
Unknown loot item ID or ticket name:
BattleHandler failed to init
```

Les bugs vont sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues), les questions
sur le [Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CobblemonDungeons](../README.fr.md) →
