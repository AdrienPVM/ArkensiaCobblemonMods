[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Symptôme, cause, remède. Le mod n'a jamais été joué : cette page est donc déduite des chemins
d'échec que le code journalise, et non d'un historique de support — mais ces chemins ont été
écrits en réaction à de vraies pannes, et les lignes de journal citées ci-dessous sont
textuelles.

Commence chaque enquête dans `logs/latest.log`, en filtrant sur le préfixe :

```bash
grep '\[CheeseRaids\]' logs/latest.log
```

---

## Le mod ne charge pas

**Cause.** Trois possibilités, par ordre de vraisemblance. Une dépendance dure manquante —
`fabric.mod.json` déclare `cheesedash` et `cheeseguard` comme obligatoires, donc Fabric refuse
de démarrer sans l'un ou l'autre. Un Fabric Loader antérieur à 0.16.0. Un Java antérieur à 21.

**Remède.** Cherche dans le rapport de crash ou le journal de démarrage le message de
dépendance de Fabric, qui nomme le mod manquant. Construis et installe les deux mods frères :

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseGuard && ./gradlew build
cd ../CheeseRaids && ./gradlew build
```

La ligne de démarrage réussi est :

```text
[CheeseRaids] initialized — modular raid framework ready.
```

suivie, une fois le monde en place, de :

```text
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

## Le mod charge mais aucun raid n'apparaît

**Cause.** `/cheeseraids status` affiche une liste vide quand `definitions` est vide. Cela
n'arrive que dans une situation dont le mod ne sait pas se relever :
`config/cheeseraids/raids/` contient des fichiers `.json` et **tous** ont échoué au parsing. Le
`hoopa.json` par défaut n'est écrit que si le dossier ne contient aucun fichier `.json` : un
seul fichier mal formé bloque donc son écriture.

**Remède.** Cherche l'erreur de parsing :

```text
[CheeseRaids] FAILED to parse raid def .../hoopa.json — fix the JSON syntax and
/cheeseraids reload. Left untouched. (…)
```

Corrige la syntaxe, ou sors le fichier du dossier et redémarre pour obtenir un défaut neuf. Le
mod n'écrase jamais un fichier mal formé : tes éditions sont toujours là.

## Une édition de configuration reste sans effet

**Cause.** Quatre candidats, et les deux premiers sont de loin les plus fréquents.

1. Le fichier n'a pas été analysé. `/cheeseraids reload` répond *Définitions de raids
   rechargées.* sans condition — elle dit la même chose que le rechargement ait fonctionné ou
   non. L'ancienne définition ne reste pas non plus en mémoire : le rechargement la retire,
   et le raid disparaît de `status` tant que le fichier ne s'analyse pas.
2. La clé ne fait rien. Seize clés du fichier ne sont lues par aucun code ; elles sont listées
   dans la [configuration](configuration.fr.md). `boss.spawnCommand`, `wave.spawnCommand`,
   `boss.provider` et `arena.gapMin`/`gapMax` sont celles vers lesquelles on se tourne en
   premier.
3. Tu as édité `arena.keepInventory` après la création de la faille. La configuration de la
   dimension n'est appliquée qu'à sa création ; change-la dans CheeseGuard.
4. Le raid tourne. Un rechargement en pleine raid change le comportement sous l'instance
   active de façon déroutante. Arrête-le d'abord.

**Remède.** Après chaque rechargement, lis la console. Puis confirme ce que le serveur détient
vraiment :

```bash
curl -s http://localhost:44311/api/v1/raids/definitions -H "Authorization: Bearer $TOKEN"
```

Cette route sérialise les objets en mémoire : c'est le seul endroit où ton édition peut être
vérifiée plutôt que supposée.

## Une commande est refusée ou invisible

**Cause.** Six des sept sous-commandes exigent le niveau de permission 2. En dessous, la
sous-commande n'apparaît pas dans la complétion par tabulation et la taper donne le vanilla
*Unknown or incomplete command*. L'exception est `/cheeseraids enter`, qui n'exige aucune
permission.

Deux autres refus ressemblent à des problèmes de permission sans en être.
*Réservé aux joueurs.* signifie qu'`enter` ou `testreward` a été lancée depuis la console ou un
bloc de commande — toutes deux exigent un joueur. Et rien ne complète l'identifiant du raid :
l'argument est un simple mot sans fournisseur de suggestions, donc une faute de frappe est
indiscernable d'un raid inconnu.

**Remède.** `/op <joueur>`, ou lance-la depuis la console pour les branches
d'administration autres que `testreward`. Prends l'identifiant exact dans `/cheeseraids status`.

## `start` refuse sans explication

**Cause.** `Impossible de démarrer '<id>' (inconnu/désactivé/déjà actif).` couvre trois
conditions différentes avec un seul message.

**Remède.** `/cheeseraids status` lève l'ambiguïté :

- l'identifiant est **absent de la liste** → inconnu. Vérifie le champ `id` à l'intérieur du
  fichier, pas le nom du fichier — c'est le champ qui gagne ;
- l'identifiant est affiché `[idle]` et `start` refuse toujours → `enabled` vaut `false` ;
- l'identifiant affiche une phase → déjà en cours. `/cheeseraids stop <id>` d'abord.

## La vague n'apparaît jamais

**Cause.** Le journal te dit lequel des trois cas s'applique.

```text
[CheeseRaids] wave arena world 'minecraft:overwrld' NOT FOUND — fix wave.arena.world.
Available: minecraft:overworld minecraft:the_nether …
```

Une faute dans l'identifiant de monde. Le message liste tous les mondes qui existent.

```text
[CheeseRaids] Cobblemon API unavailable; cannot spawn 'hoopa level=80 aspect=unbound uncatchable=true'
```

Cobblemon est absent ou son API `PokemonProperties` a bougé. Note que Cobblemon n'est **pas**
déclaré en dépendance : rien ne t'a averti au démarrage.

```text
[CheeseRaids] spawnEntity refused 'sableye level=60 uncatchable=true' at 112.3,72.0,-2043.1
(chunk not loaded / build limit?)
```

Le chunk n'est pas chargé — personne n'est près de l'arène de vague — ou la position dépasse la
limite de construction.

**Remède.** Respectivement : corrige `wave.arena.world` ; installe Cobblemon ; sois sur place,
ou choisis une arène dans une zone chargée en permanence. Un raid avec zéro mob apparu ne se
bloque pas — il journalise `spawned 0 wave mob(s) — proceeding.` et avance.

## Le héraut n'apparaît jamais

**Cause.** La révélation cherche les entités dont l'identifiant de registre commence par
`easy_npc`, dans les 10 blocs de `npc.pos`, dans le monde désigné par `npc.pos.world`. Quatre
choses la cassent : mauvaises coordonnées, mauvais monde, chunk non chargé, ou changement de
l'API d'affichage d'EasyNPC.

**Remède.** Le diagnostic sépare les quatre :

```mcfunction
/cheeseraids herald hoopa info
```

- `Monde '<id>' non chargé.` → mauvais identifiant de monde ;
- `EasyNPC dans 10 blocs: 0` plus `Autres EasyNPC dans 128 blocs (mauvaise position ?)` →
  faute de frappe dans les coordonnées, et la liste te dit où le NPC se trouve réellement ;
- `⚠ AUCUN EasyNPC chargé à proximité` → rien dans les 128 blocs non plus. En général un chunk
  non chargé : place-toi à côté du NPC et relance ;
- une liste correcte mais `0 toggled via display-attr` dans la console → EasyNPC est trouvé et
  l'API de visibilité a changé. Journalise aussi `setDisplayAttribute(VISIBLE,...) not found`.

Cet échec n'arrête pas le raid. Il annonce `/cheeseraids enter <id>` dans le chat à la place,
et c'est pourquoi un héraut cassé peut passer inaperçu pendant tout un événement.

## Les joueurs ne peuvent pas entrer alors que le héraut est visible

**Cause.** C'est le piège le plus probable. **CheeseRaids ne donne aucun dialogue au héraut.**
Il rend le NPC visible et luisant ; il ne contient aucun gestionnaire d'interaction et aucune
liaison d'action EasyNPC. Le chat dit aux joueurs *parlez-lui*, et rien ne se passe quand ils le
font, parce que rien n'a jamais été lié.

**Remède.** Configure toi-même le dialogue du NPC dans EasyNPC, avec une action qui lance
`/cheeseraids enter <raid>`. En attendant, dis aux joueurs de taper la commande. Rien dans ce
dépôt ne le fait pour toi et rien ne vérifie que c'est fait.

## `enter` est refusée

| Message | Cause | Remède |
|---|---|---|
| `Aucune invasion active.` | aucune instance sous cet identifiant | `/cheeseraids status` ; démarre le raid |
| `La faille n'est pas ouverte.` | le raid tourne mais n'est pas en `OPEN` — la vague est encore debout, ou le combat de boss a déjà commencé | attends, ou accepte que l'entrée soit fermée |
| `La faille n'a pas pu s'ouvrir.` | la dimension de faille n'a pu être ni créée ni résolue | console : `Failed to create rift dimension cheeseguard:<id>`. Vérifie que CheeseGuard est chargé et que `arena.riftDimId` est un identifiant légal |
| `La faille est pleine.` | les `arena.maxSlots` slots sont pris | augmente `maxSlots`, ou attends le raid suivant |
| `Réservé aux joueurs.` | lancée depuis la console | lance-la en tant que joueur |

## Le chemin de parkour n'apparaît pas

**Cause.** La branche est vidée puis animée à `arena.revealBlocksPerTick` blocs par tick, après
`arena.revealDelayTicks` ticks de montée en puissance — trois secondes entières de particules
avant que quoi que ce soit ne soit posé, par défaut (60 ticks). Un chemin long à huit blocs par tick prend un
moment. Si rien n'apparaît du tout après ce délai, la file de révélation a été vidée :
`clearReveals` tourne au nettoyage, et `/cheeseraids stop` le déclenche.

**Remède.** Attends la fin du délai. Augmente `revealBlocksPerTick` si le rythme ne te convient
pas. Si un joueur se tient sur une branche vide après un arrêt, ce raid est terminé —
téléporte-le dehors.

## Le boss n'apparaît pas

**Cause.** Trois, chacune avec sa ligne de journal.

```text
[CheeseRaids] Boss rift world cheeseguard:hoopa_rift not loaded
```

La faille `cheeseguard:` + `arena.riftDimId` n'existe pas ou n'est pas chargée
(`arena.center.world` n'y est pour rien : il n'est jamais lu).

```text
[CheeseRaids] Boss spawn FAILED for hoopa — see preceding log.
```

L'apparition Cobblemon a été refusée ; la ligne précédente dit pourquoi. Les deux causes contre
lesquelles le code se défend déjà sont l'asphyxie (une poche d'air est creusée au préalable,
dimensionnée depuis `boss.baseScale`) et le déchargement du chunk central pendant que les
joueurs sont sur le parkour (le chunk est forcé en mémoire).

Aucune ligne de journal du tout, avec le raid bloqué en `COMBAT` : une exception de tâche
d'effets a abandonné l'apparition avant qu'elle atteigne le tick 36, où a lieu la création.
Cherche `FX task error` juste avant.

**Remède.** Cherche `Failed to create rift dimension` plus haut dans le journal et vérifie que
CheeseGuard est chargé. Installe Cobblemon. Pour le troisième cas, le raid se résout de lui-même après
`boss.combatTimeoutSeconds` — 15 minutes par défaut, donc arrête-le à la main.

## Le boss a disparu et personne n'a gagné

**Cause.** Le fournisseur tente de distinguer un K.O. d'une disparition. Un boss parti dans les
200 ticks (dix secondes) suivant son apparition, sans combat enregistré, est traité comme un
échec d'apparition et non comme une victoire, et il réapparaît jusqu'à deux fois :

```text
[CheeseRaids] Boss for hoopa vanished pre-battle — respawning (1/2).
```

Passé deux réapparitions, ou pour une disparition plus de dix secondes après l'apparition,
plus rien ne réapparaît — et le raid n'attend **pas** le timeout de combat. Sans combat
enregistré, un boss absent une fois écoulées cinq secondes depuis sa dernière apparition
compte comme un K.O. : le raid passe en `REWARD` et tous les joueurs connectés sont
récompensés alors que personne n'a gagné.

**Remède.** Une disparition répétée avant combat traduit une asphyxie ou une chute dans le
vide. Augmente `arena.center.y`, ou vérifie que rien d'autre n'écrit de blocs dans la faille.

## Aucun portail de victoire ne s'est ouvert

**Cause.** Quatre, dans l'ordre où le code les essaie.

```text
[CheeseRaids] No Hoopa portal opened (2 earned) — CobbleSafari absent, or it requires the
overworld (anchor is in <world>).
```

CobbleSafari n'est pas installé, ou ses méthodes de `PortalSpawnManager` n'ont pu être résolues
(`CobbleSafari portal methods not found` à l'initialisation), ou l'ancrage est hors de
l'overworld et seul le secours limité à l'overworld était disponible, ou tous les participants
se sont déconnectés avant la récompense — l'appel de portail exige un joueur connecté comme
propriétaire.

**Remède.** Teste la position et la dépendance sans lancer de raid :

```mcfunction
/cheeseraids testreward hoopa
```

`0/1` confirme le côté dépendance ; `Monde d'ancrage introuvable: <id>` confirme l'identifiant
de monde. En dernier recours, règle `reward.portalCommand` sur quelque chose qui fonctionne sur
ton serveur — elle ne s'exécute que si CobbleSafari n'a rien ouvert, et dispose les portails en
ligne plutôt qu'en cercle.

## Les récompenses sont allées à quelqu'un qui n'a rien fait

**Cause.** Ce n'est pas un bug, c'est un manque de conception. Tout joueur de l'instance
**connecté** à la mort du boss reçoit les feux d'artifice, le fromage et chaque entrée de
`reward.commands`. Le drapeau `arrived` est suivi et le chemin de récompense ne le lit pas.

**Remède.** Aucun en configuration. Il faut un changement de code dans `RewardManager`.

## Des joueurs sont bloqués dans la faille après un arrêt

**Cause.** Ni `/cheeseraids stop` ni `POST /api/v1/raids/stop` ne téléportent personne. Le
nettoyage remplit d'air chaque branche et vide l'arène : un joueur en plein parkour se retrouve
debout dans une dimension vide dont le sol vient d'être supprimé — et cette dimension a
`enabled: false`, donc il ne peut pas en sortir par un portail.

**Remède.** Téléporte-les toi-même avant d'arrêter, ou après :

```mcfunction
/execute in minecraft:overworld run tp <joueur> <x> <y> <z>
```

Puis, quand c'est possible, laisse le chemin de victoire conclure : il éjecte tout le monde
proprement.

## Le module du dashboard est absent

**Cause.** Le module est chargé via l'entrypoint custom Fabric `cheesedash`. Si la barre
latérale n'a pas d'entrée **CheeseRaids**, c'est soit que le jar CheeseRaids n'est pas dans
`mods/` du tout, soit que CheeseDash a démarré sans le découvrir.

**Remède.** Vérifie que les deux jars sont présents et que le journal de démarrage montre
CheeseDash chargeant ses modules. Puis interroge le préfixe directement :

```bash
curl -s http://localhost:44311/api/v1/raids/ -H "Authorization: Bearer $TOKEN"
```

Un `503 {"error":"CheeseRaids not ready"}` signifie que le module est chargé mais que le serveur
n'a pas fini de démarrer — attends. Un `404`, avec `raids` absent de `/api/v1/modules`, signifie
que l'entrypoint n'a pas été découvert.

## Le dashboard affiche des données périmées

**Cause.** Le canal WebSocket `raids` ne porte qu'un seul type de message, `raid_update`, et il
n'est émis qu'aux **transitions de phase**. Rien ne diffuse quand un joueur entre, franchit un
checkpoint, ou quand le boss apparaît — et la vue livrée n'écoute même pas ce canal (voir
[la page dashboard](dashboard.fr.md#websocket)). La table des participants n'est redessinée
que quand tu appuies sur *Rafraîchir* ou utilises l'un des boutons de la vue.

**Remède.** Appuie sur *Rafraîchir*. C'est une limitation connue, pas un problème de connexion.

## Un raid planifié ne s'est pas déclenché

**Cause.** Le planificateur n'est pas un cron. Il interroge une fois par seconde et ne
déclenche que si l'heure **et** la minute locales du serveur correspondent toutes deux : un
serveur arrêté ou en redémarrage pendant toute cette minute rate le déclenchement, sans
rattrapage. Il ignore aussi un raid déjà actif, un raid avec `enabled: false`, et un raid dont
l'horodatage dans `state.json` a moins de `everyNDays` jours.

**Remède.** Vérifie qu'`autoTrigger` vaut `true` via `/cheeseraids status`, qui affiche
`(auto HHhMM)`. Vérifie le fuseau horaire du serveur, pas le tien. La ligne de déclenchement
est :

```text
[CheeseRaids] Scheduler triggers raid 'hoopa'
```

Si `state.json` porte un horodatage issu d'un test, supprime l'entrée pour autoriser un
déclenchement immédiat.

---

## Signaler un bug

Ouvre une issue sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **Toutes les lignes `[CheeseRaids]` de la session**, pas seulement l'erreur :
   ```bash
   grep '\[CheeseRaids\]' logs/latest.log > cheeseraids.log
   ```
   Les transitions de phase elles-mêmes ne sont pas journalisées, mais les étapes autour le
   sont (vague apparue, vague terminée, boss apparu, récompenses, nettoyage), ainsi que chaque
   échec de hook : la séquence entière est beaucoup plus utile que la dernière ligne.
2. **La définition du raid**, depuis `GET /api/v1/raids/definitions` plutôt que depuis le
   fichier — c'est ce que le serveur a réellement en mémoire. N'en masque rien ; elle ne
   contient aucun secret.
3. **Le fichier d'instance** si un raid est bloqué :
   `config/cheeseraids/instances/<raidId>.json`. Il contient toutes les échéances, les
   identifiants d'entité et de combat du boss, et le checkpoint de chaque joueur — rien de tout
   cela n'étant exposé par l'API REST.
4. **Les mods facultatifs installés**, avec leurs versions : Cobblemon, EasyNPC, CobbleSafari,
   CheeseStore. Quatre dépendances du mod sont non déclarées et atteintes par réflexion : un
   écart de version est une cause probable, et invisible dans les seuls journaux.
5. **La phase en cours**, depuis `/cheeseraids status` ou la carte du dashboard.

Pour un crash, joins aussi `crash-reports/` en plus du journal.

Les questions plutôt que les bugs → [Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CheeseRaids](../README.fr.md) →
