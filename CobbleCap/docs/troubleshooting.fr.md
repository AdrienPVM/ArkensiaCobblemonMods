[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

## Le mod n'apparaît pas du tout

**Cause.** Soit le jar n'est pas chargé, soit il y en a deux.

**Remède.** Cherche `[CobbleCap] Initialized` dans le log serveur. L'absence de cette ligne
signifie que Fabric n'a jamais lancé l'initialiseur : vérifie que
`cobblecap-1.0.0+1.21.1.jar` est dans `mods/`, que Fabric API y est aussi, et que le loader
est au moins en 0.15.11. Si le démarrage a franchement échoué avec une erreur de mod
dupliqué, tu as un ancien `cobblecap-*.jar` à côté du nouveau — supprime l'ancien, ne le
renomme pas.

## Le démarrage plante avec une erreur de mixin

**Cause.** `cobblecap.mixins.json` pose `injectors.defaultRequire: 1`, donc chaque point
d'injection est obligatoire. Une méthode cible qui ne correspond plus est un échec dur.

**Remède.** Lis le mixin nommé par le crash.

- `PokemonSetLevelMixin` ou `PokemonAddExperienceMixin` — Cobblemon a changé
  `Pokemon.setLevel`, `addExperience` ou `addExperienceWithPlayer`. CobbleCap est construit
  contre Cobblemon 1.6.1 ; un Cobblemon plus récent exige de mettre le mixin à jour.
- `BipedEntityModelMixin`, `PlayerEntityModelMixin`, `BipedAnimateModelMixin`,
  `PlayerEntityRendererMixin` ou `EntityRenderDispatcherMixin` — ce sont des mixins
  **client** et un décalage de version Minecraft les cassera. Ils sont cosmétiques, mais
  `defaultRequire` les rend fatals quand même.

Il n'existe aucune option de configuration pour désactiver un mixin. Les seuls contournements
sont de respecter les versions supportées, ou d'éditer `cobblecap.mixins.json` et de
reconstruire.

## La limite d'XP ne fait rien

**Cause.** Les hooks d'événement Cobblemon ne se sont pas liés, ou le système de limite est
coupé, ou il n'y a pas d'entrée joueur.

**Remède.** Descends cette liste dans l'ordre.

1. Cherche les quatre lignes de hook dans le log :

   ```text
   [CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
   [CobbleCap] Cobblemon candy XP hook registered on field: EXPERIENCE_CANDY_USE_PRE
   [CobbleCap] Battle pre-check hook registered on field: BATTLE_STARTED_PRE
   [CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
   ```

   `[CobbleCap] Cobblemon not found — XP cap enforcement disabled` signifie que Cobblemon
   n'est pas installé. `Could not find XP event field in CobblemonEvents` signifie que
   Cobblemon a renommé le champ et que cette couche est coupée — c'est la ligne à vérifier
   après chaque mise à jour de Cobblemon.
2. `/cobblecap status <player>` et lis `System enabled`. `false` signifie que quelqu'un a
   lancé `/cobblecap disable` ou que le dashboard a posé `enabled: false`. Réactive avec
   `/cobblecap enable`.
3. Lis `Cap level` sur le même écran. Si le joueur n'est pas du tout dans les données tu
   obtiens `Joueur <nom> introuvable dans les données CobbleCap.` — fais-le se reconnecter,
   ce qui appelle `ensureExists`.
4. Ne juge pas d'après le message de la barre d'action. L'XP de combat d'un Pokémon
   plafonné est mise à zéro par `PokemonAddExperienceMixin` avant que Cobblemon ne déclenche
   son événement d'XP : elle est donc refusée **sans message** même quand tout fonctionne ;
   le message n'apparaît que lorsqu'un bonbon exp est utilisé sur un Pokémon à la limite ou
   au-dessus. Teste avec un bonbon.

## Un Pokémon reste à un niveau sous la limite

**Cause.** C'est le comportement correct, pas un bug.

**Remède.** Rien. `PokemonAddExperienceMixin` calcule la marge comme
`xpForCapLevel − currentXp − 1`, laissant le Pokémon à un point d'expérience du niveau
plafond pour que le chemin de montée de niveau de Cobblemon ne se déclenche jamais. Une
limite de 30 signifie que les Pokémon s'arrêtent à **29** avec une barre presque pleine.
[Pourquoi](level-cap.fr.md#2-pokemonaddexperiencemixin--la-couche-de-rognage).

## L'XP est bloquée alors que le joueur est sous la limite

**Cause.** Un blocage d'XP, indépendant de la limite et de `enabled`.

**Remède.** `/cobblecap status <player>` et lis `XP locked`. Lève-le avec
`/explock <player>` — c'est une **bascule**, donc lance-la une fois et revérifie. Le joueur
voit `🔒 %s — XP bloquée` dans la barre d'action au lieu du message habituel de limite, ce
qui permet de les distinguer.

## Un champion ne fait rien au clic droit

**Cause.** Presque toujours, l'identifiant de dresseur dans `champions.json` ne correspond
pas à l'entité.

**Remède.**

1. `/cobblecap reload` et lis le compte. Aucun compte, seulement l'erreur vanilla
   *An unexpected error occurred trying to execute that command*, signifie que le JSON
   contient une erreur de syntaxe et que l'ancienne liste est encore active — l'exception est
   dans le log serveur. Un compte inférieur au nombre d'entrées du fichier signifie qu'une
   entrée a une valeur mal typée et que seules les entrées qui la précèdent ont été
   chargées.
2. Clic droit sur le dresseur et cherche la ligne de diagnostic :

   ```text
   [CobbleCap] BATTLE_PRE: entity=TrainerMob trainerId=gym_leader_pierre_0395 (raw=gym_leader_pierre_0395)
   ```

   Compare `trainerId` à ton `id` caractère par caractère. Si `raw` diffère de `trainerId`,
   un suffixe de dimension a été retiré — c'est attendu à l'intérieur d'une dimension
   Conseil 4 par joueur.
3. `trainerId=null` signifie que `extractRctmodTrainerId` n'a rien trouvé sur cette entité.
   Aucune ligne du tout signifie qu'aucun combat n'a démarré, ou que le hook de pré-combat
   ne s'est pas branché (voir [plus haut](#la-limite-dxp-ne-fait-rien)). L'entité doit aussi être dans le
   namespace `rctmod` pour que `UseEntityCallback` la regarde ne serait-ce qu'une fois.
4. Confirme que l'entrée est chargée avec `/cobblecap status <player>` — la liste des
   champions y est affichée en entier avec `✓`/`✗` par entrée.

## Un combat de champion est refusé

**Cause.** L'une de quatre conditions. Chacune produit un message différent.

**Remède.** Associe le message :

| Message | Condition | Lever avec |
|---|---|---|
| `Vous ne pouvez pas défier X — vous devez d'abord vaincre Y.` | ordre — X n'est pas `nextUnbeaten` | battre Y, ou `/cobblecap addbeaten <player> "<id de Y>"` |
| `Vous ne pouvez pas défier X — conditions non remplies :` puis `Pokédex : n/m capturés` | `requiredPokedex` | en capturer plus, ou baisser le champ |
| `… Complétez une quête requise d'abord` | `requiredQuestId` | terminer la quête FTB |
| `… Tu dois d'abord parler à Alfred` | `requiredScoreboard` / `requiredScoreMin` | relever le score. Le texte du message est spécifique à Arkensia — voir plus bas |
| `X — «Quel beau combat c'était ! …»` | déjà battu, aucun défi débloqué | rien ; `allowRefight` n'y change rien |

`Vous avez déjà vaincu X.` n'est pas un refus que tu rencontreras ici. `handlePreBattle`
l'afficherait pour un champion battu avec `allowRefight: false`, mais chaque appelant vérifie
d'abord `championsBeaten` et ne lui transmet jamais un champion battu : en pratique, la ligne
n'apparaît que lors d'une **première** victoire quand la limite était déjà égale ou
supérieure à `capGranted`.

`/cobblecap status <player>` affiche chaque condition avec `OK`/`FAIL` et la valeur courante,
ce qui est plus rapide que de devenir devin. Noter que le `/cobblecap` côté joueur **n'affiche
pas** la condition de scoreboard — seule la vue admin le fait.

Le libellé `Tu dois d'abord parler à Alfred` est la clé de langue
`cobblecap.msg.condition_progress`, écrite pour le PNJ de quête d'Arkensia. Si ta condition
de scoreboard signifie autre chose, édite
`src/main/resources/assets/cobblecap/lang/{en_us,fr_fr}.json`.

## Une condition de Pokédex ne peut jamais être satisfaite

**Cause.** `getPokedexCount` échoue en mode **fermé** — il renvoie `0` sur tout échec de
réflexion, ce qui rend toute condition de Pokédex infranchissable.

**Remède.** `/cobblecap status <player>` affiche `pokedex: 0/80 FAIL` pour un joueur qui en a
manifestement plus. Cette incohérence est la signature. Le log serveur contient
`[CobbleCap] getPokedexCount error: …` en niveau debug — active la journalisation debug pour
le logger `cobblecap` pour le voir.

C'est le seul échec de réflexion du mod qui bloque les joueurs au lieu de les laisser passer.
En attendant un correctif, contourne-le en mettant `requiredPokedex` à `0` et en
conditionnant sur un scoreboard à la place.

## Une victoire n'a pas relevé la limite

**Cause.** La victoire n'a pas été détectée, ou la limite était déjà plus haute.

**Remède.**

1. Si la limite était déjà égale ou supérieure à `capGranted`, `raiseCapLevel` renvoie
   `false` par conception et le joueur reçoit `Vous avez déjà vaincu X.` au lieu de la ligne
   de victoire. C'est correct.
2. Sinon le hook `BATTLE_VICTORY` n'a pas identifié le combat. Vérifie que la ligne de hook
   existe (voir [plus haut](#la-limite-dxp-ne-fait-rien)), puis répare à la main :

   ```mcfunction
   /cobblecap addbeaten Steve "gym_leader_pierre_0395"
   ```

## `/cobblecap set` a relevé la limite mais le joueur ne progresse toujours pas

**Cause.** `set` n'écrit que le nombre. `championsBeaten` n'est pas touché, donc la
condition d'ordre croit toujours que le joueur est à son ancien point.

**Remède.** Le log serveur le dit explicitement, et nomme la commande :

```text
[CobbleCap] Order-check rejection may be a desync: player=Steve cap=50 tried=gym_leader_erika_0399 expectedNext=gym_leader_ondine_0396 championsBeaten=[…]. If the player legitimately beat prior champions, repair with: /cobblecap addbeaten Steve gym_leader_ondine_0396
```

Lance ce `addbeaten` pour chaque champion qu'il devrait avoir battu. Préfère `addbeaten` à
`set` dès le départ — il relève la limite *et* enregistre la progression.

## Un dresseur continue de proposer le combat de défi

**Cause.** `swapToChallengeBattle` pose l'identifiant du TrainerMob sur la variante défi et
le restaure deux sauts de `server.execute` plus tard. Si l'entité s'est déchargée ou si le
serveur s'est bloqué entre-temps, l'identifiant reste coincé.

**Remède.** Cela se répare tout seul. `CapListener` détecte un identifiant de défi sur une
entité dont le joueur n'a pas battu le champion normalement, restaure l'identifiant normal —
en préservant tout suffixe de dimension — et traite l'interaction comme un combat normal :

```text
[CobbleCap] Entity has stuck challenge ID 'gym_leader_pierre_challenge' but player hasn't beaten 'gym_leader_pierre_0395' — restoring to 'gym_leader_pierre_0395'
```

Si cela ne se résorbe pas, pose l'identifiant à la main avec l'outillage propre à rctmod, ou
tue et replace le PNJ.

## Personne n'est jamais couronné Maître de la Ligue

**Cause.** L'identifiant du champion final n'est pas `champion_regis_challenge`.

**Remède.** Cette chaîne est codée en dur à quatre endroits — `ChampionManager` lignes 548,
605 et 792, et `StatueCommand` ligne 541. Soit tu renommes l'`id` de ton champion final en
`champion_regis_challenge` dans `champions.json` et dans ton datapack rctmod, soit tu édites
ces quatre lignes et reconstruis.

Le symptôme sur `chrono stop` est explicite :

```text
[CobbleCap] Chrono stop for Steve REFUSED — champion_regis_challenge not beaten this run; bestTime ignored, no legend created
```

et le joueur reçoit la ligne française
`⚠ Le chrono a été arrêté mais tu n'as pas (encore) battu Régis Challenger…`.

## Le morphing du champion ne fait rien

**Cause.** Le datapack n'a pas été trouvé. C'est le problème le plus probable sur tout
serveur qui n'est pas Arkensia.

**Remède.** Le log nomme chaque chemin essayé :

```text
[CobbleCap] Datapack 'CacademyMons' not found in any of: [/srv/mc/world/datapacks/CacademyMons, …]
```

`datapackName` est codé en dur à `"CacademyMons"` dans
`src/main/java/com/cobblecap/logic/RctTrainerMorpher.java` **ligne 57**. Ce n'est **pas** une
clé de configuration, malgré le commentaire au-dessus qui l'affirme. Édite le source,
reconstruis, redéploie. Détail complet dans
[le morphing du champion](champion-morphing.fr.md#ce-que-tu-dois-changer).

Une fois le chemin résolu tu devrais voir :

```text
[CobbleCap] Datapack root resolved: /srv/mc/world/datapacks/TonPack
[CobbleCap] Trainer JSON rewritten: …/champion_regis_challenge.json (6 Pokémon)
[CobbleCap] rctmod TrainerManager.loadTrainers() invoked (ok=true)
```

## L'équipe morphée n'apparaît qu'après un redémarrage

**Cause.** Le rechargement rctmod par réflexion a échoué. Le fichier sur le disque est
correct ; le registre en mémoire de rctmod est périmé.

**Remède.**

```text
[CobbleCap] rctmod reload failed after morph -> Steve — JSON on disk updated, restart to apply.
[CobbleCap] rctmod reload via reflection FAILED: <exception>
```

Rien n'est corrompu. Soit tu redémarres, soit tu répares la réflexion : `RctReloadHook` cible
`com.gitlab.srcmc.rctmod.api.RCTMod.getInstance().getTrainerManager().loadTrainers()`,
vérifié contre rctmod-fabric 0.16.11-beta. Une autre version de rctmod peut l'avoir déplacé.
Ne te rabats **pas** sur `/reload` — un rescan de datapack vanilla gèle un gros serveur moddé
pendant plusieurs secondes, ce que ce hook existe précisément pour éviter.

## Le visage du champion ne change jamais

**Cause.** Comportement correct. Le morph de la skin n'est **pas implémenté**.

**Remède.** Rien à corriger. `flushMorph` journalise
`[CobbleCap] Skin morph skipped (architectural limitation — see RctTrainerMorpher comment)`.
Un datapack ne peut pas servir de textures — les assets viennent des packs de ressources — et
écrire dans `resourcepacks/` forcerait chaque client à retélécharger le modpack. L'équipe
change, le visage non. [Pourquoi](champion-morphing.fr.md#la-skin-nest-pas-écrite).

## Deux joueurs affrontent des variantes du Conseil 4 autres que leur choix

**Cause.** Il y a un seul JSON de dresseur actif et il est global au serveur, alors que
`cobblecap_mode` est par joueur.

**Remède.** Il n'y a aucun correctif par configuration — c'est une limite de conception,
assumée dans le source. Deux entrées à une seconde d'écart avec des modes opposés atterrissent
toutes deux sur celui écrit en dernier.
[Détails](champion-morphing.fr.md#le-basculement-de-mode-de-régis-est-global). Sérialise les
entrées, ou accepte-le comme Arkensia le fait à cinq ou dix entrées par semaine.

## Une statue est invisible, ou s'affiche en Steve

**Cause.** Plusieurs, par ordre de probabilité approximatif.

**Remède.**

1. **Aucun profil avec textures.**

   ```text
   [CobbleCap] No profile with textures for Steve (f84c6a79-…) — doppel will not render
   ```

   `MojangProfileFetcher` a essayé le joueur en ligne, le cache utilisateur, le cache de
   session et une requête Mojang directe, et les quatre ont échoué. Sur un serveur en mode
   hors ligne ou sans réseau sortant, il n'y a rien à récupérer.
2. **La doublure n'a pas été renvoyée.** Éloigne-toi au-delà de 160 blocs puis reviens ;
   l'auto-réparation de 3 secondes appelle `ensureVisibleToNearby(128.0)` et devrait la
   rattacher. Le log le dit :

   ```text
   [CobbleCap][Doppel] auto-heal: shown to 1 new viewer(s) across 2 doppel(s)
   ```
3. **Le repli Steve précisément** signifie que deux entités joueur ont partagé un UUID et ont
   embrouillé le cache de textures du client. Le mod dédoublonne à la création
   (`[CobbleCap][Doppel] DEDUP: …`), mais un client qui a déjà mis en cache la mauvaise skin
   doit se reconnecter.
4. **C'est un tracker orphelin.** Un support d'armure marqueur invisible dont la doublure ne
   s'est jamais rattachée est introuvable à la main. `/cobblecap statue wipe_all` est basé sur
   les étiquettes et le nettoiera ; replace ensuite.

## Une statue est stylisée mais ressemble à une skin normale

**Cause.** Le spectateur n'a pas CobbleCap sur son client.

**Remède.** La texture de corps stylisée vient de `PlayerEntityRendererMixin` et des PNG du
jar, donc c'est côté client. La couleur de lueur et l'aura de particules sont côté serveur et
visibles de tout le monde. Installe le mod sur le client, ou accepte que les captures d'écran
diffèrent entre joueurs.

Une statue `natural` affiche volontairement la vraie skin — `StatueStyleClient` stocke
`natural` comme absent, ce qui la fait retomber sur la résolution vanilla.

## Un diorama `place_current` n'a pas de rangée de Pokémon

**Cause.** Aucun opérateur n'était en ligne quand le rafraîchissement a tourné.

**Remède.** La commande `/spawnpokemonat` de Cobblemon appelle
`source.getPlayer().getUuid()` et échoue sans joueur, donc `spawnDioramaAtAnchor` emprunte
n'importe quel joueur de niveau 2 en ligne comme source de commande. `findOnlineOp` renvoyant
`null` signifie que la figure du dresseur apparaît et que la rangée de Pokémon non. Lance
`/cobblecap champion refresh_dioramas` pendant qu'un opérateur est connecté.

L'autre possibilité est un champion résolu par le troisième barreau de l'échelle de repli —
synthétisé depuis le seul `Tenure`, avec la bonne identité mais **aucune équipe**. Voir
[qui est « le champion courant »](statues.fr.md#qui-est--le-champion-courant-).

## `statue move` annonce `Moved 0 entit(y/ies)`

**Cause.** Le `slot` ou le `playerName` n'a rien trouvé.

**Remède.** `slot` sur `move`, `rotate`, `scale` et `style` est un **mot**, pas un entier :
`player`, `p`, `1`–`6`, ou `all`. Une valeur non reconnue est acceptée et n'affecte rien.
`remove_slot` n'accepte en outre **pas** `all`. Vérifie que le diorama existe avec
`/cobblecap statue list`, et rappelle-toi que la clé est le **nom** de joueur en minuscules —
un changement de pseudo orpheline le diorama.

## `/pasturehide` ne masque rien

**Cause.** Le client n'a aucune donnée de pâturage venant du serveur.

**Remède.** Active-la et lis le log client :

```text
[CobbleCap-Client] /pasturehide last 1s: checked 42 Pokémon entities, hid 7
```

- aucune ligne alors que la bascule est active et que des Pokémon sont en vue — le mixin ne
  voit aucune entité Pokémon. La ligne n'est affichée que si au moins un Pokémon a été
  vérifié, donc `checked 0` n'apparaît jamais. Cobblemon est absent sur le client, ou le nom
  de classe de l'entité n'est pas `…PokemonEntity`.
- `checked 42, hid 0` — le mixin fonctionne et l'ensemble côté client est vide. Le serveur n'a
  pas envoyé d'instantané : il n'a pas CobbleCap, ou `PastureTracker` n'a pas fait son premier
  parcours (toutes les 40 ticks), ou les Pokémon ne sont réellement pas attachés.
- aucune ligne et rien en vue non plus — vérifie que la bascule est active :
  `/pasturehide status`.

À la connexion, le client devrait journaliser
`[CobbleCap-Client] Pasture snapshot received: N tethered Pokémon`. En son absence, le
problème est côté serveur.

## Le module dashboard est absent

**Cause.** CheeseDash n'est pas installé, ou l'entrypoint n'a jamais tourné, ou le serveur
n'était pas prêt.

**Remède.**

1. CheeseDash doit être dans `mods/`. Il n'est qu'en `suggests` à l'exécution, donc son
   absence est silencieuse — le module ne s'enregistre simplement jamais.
2. Un `503 {"error":"CobbleCap not ready"}` signifie que CheeseDash est en marche mais que
   soit son `onServerStart` ne s'est pas déclenché, soit `CobbleCapMod.INSTANCE` est nul.
   Attends la fin du démarrage ; si cela persiste, l'initialiseur de CobbleCap n'a pas tourné
   — voir le premier symptôme de cette page.
3. Vérifie que `fabric.mod.json` porte
   `"cheesedash": ["com.cobblecap.dashboard.CobbleCapDashboardModule"]`. C'est le cas dans une
   build standard.

## Un compte `READ_ONLY` du dashboard peut modifier les limites

**Cause.** Tu fais tourner une build antérieure au **29 octobre 2026**.

**Remède.** Mets à jour. Les quatre gardes d'écriture de `CobbleCapApi` étaient écrits
`WebDashboard.requireWrite(exchange);` en instruction nue, en jetant la valeur retournée, si
bien que le 403 était envoyé et que le gestionnaire continuait quand même. Ils sont désormais
`if (!WebDashboard.requireWrite(exchange)) return;`. En attendant de pouvoir mettre à jour, ne
donne de compte `READ_ONLY` à personne — traite chaque compte comme OPERATOR.
[Détails](dashboard.fr.md#rôles).

## Le dashboard annonce un succès mais la valeur est différente

**Cause.** Les setters de feux d'artifice plafonnent silencieusement.

**Remède.** `fireworksCount` est plafonné à `max(1, v)`, `fireworksIntervalMs` à
`max(50, v)` et `fireworksDurationSec` à `max(1, v)`. Un `PUT` renvoie `{"updated": true}`
avec la valeur que tu as envoyée tout en stockant la valeur plafonnée. Refais un
`GET /api/v1/cobblecap/config` pour voir ce qui a réellement été retenu.

`startingCap` est un cas différent : le `GET` le renvoie, le `PUT` l'ignore entièrement.
Édite `config/cobblecap/config.json` et lance `/cobblecap reload` (ou redémarre).

## Les feux d'artifice ne partent jamais

**Cause.** Désactivés, pas de zone, une zone non résolvable, ou le plafond de durée.

**Remède.**

```text
[CobbleCap] Fireworks enabled but no zone configured — skipped        (niveau debug)
[CobbleCap] Fireworks zone 'league_arena' not found via CheeseGuard — show aborted
[CobbleCap] Fireworks show already running — new request coalesced
[CobbleCap] Fireworks show hit duration cap at rocket 12/200
```

Règle le tir avec `POST /api/v1/cobblecap/fireworks/test`, qui ignore `fireworksEnabled` mais
exige quand même un identifiant de zone. CheeseGuard est atteint par réflexion ; sans lui la
zone ne peut pas se résoudre, et tu obtiens le même avertissement
`Fireworks zone '…' not found via CheeseGuard — show aborted` (plus une ligne de debug
indiquant que CheeseGuard n'est pas installé). Et rappelle-toi que
le spectacle ne se déclenche que sur une victoire **compétitive**, jamais sur une classique.

## Les scoreboards Conseil 4 sont vides ou faux

**Cause.** La mauvaise convention de signe, ou des lignes indexées par nom.

**Remède.** Les scores sont volontairement inversés pour qu'un tri décroissant de barre
latérale vanilla affiche l'ordre voulu :

| Objectif | Score stocké |
|---|---|
| `conseil4_first_finish` | `nombre − index` — le plus ancien vainqueur le plus haut |
| `conseil4_fastest_time` | secondes **négatives** — le plus rapide le plus haut |
| `conseil4_ranked_champions` | `startedAt / 1000` |
| `conseil4_ranked_failures` | le nombre brut d'échecs |

Un consommateur externe qui lit `conseil4_fastest_time` brut doit le renégativer. Préfère les
exports JSON dans `config/cobblecheesescore_external_conseil4_*.json`, qui portent des
valeurs réelles et un temps déjà formaté.

Les lignes sont indexées par **nom** de joueur, pas par UUID, donc un changement de pseudo
orpheline une ligne jusqu'à la reconstruction suivante. Forces-en une avec tout événement qui
appelle `Conseil4Boards.rebuild` — un `chrono stop`, ou un redémarrage.

Si les quatre sont vides après un redémarrage, c'était un bug d'une build antérieure qui
écrivait des tableaux vides au démarrage. L'`initObjectives` actuel appelle `rebuild` depuis
le stockage persistant.

## Un chrono a survécu à un redémarrage et affiche un temps absurde

**Cause.** Comportement correct. `cobblecap_chronos.json` persiste l'**horodatage de
départ**, et la pendule n'est pas mise en pause pendant que le serveur est arrêté.

**Remède.** `/cobblecap chrono cancel <player>`, ou `/cobblecap chrono start <player>` pour le
relancer — `start` a une sémantique de redémarrage forcé et écarte toujours une course en
cours. Si tu redémarres pendant une course, annule les chronos concernés avant que quiconque
atteigne la fin.

## De la progression a été perdue après un crash

**Cause.** Le différé de 500 ms sur `players.json`, ou un `cobblecap_tenures.json` tronqué.

**Remède.** `CapStorage.saveAsync` planifie une écriture complète 500 ms après un changement,
donc un crash brutal peut perdre cette fenêtre — une hausse de limite, réalistement. Un arrêt
propre ne perd rien (`SERVER_STOPPING` appelle un `save()` synchrone).

`cobblecap_tenures.json` est le plus risqué : contrairement à `players.json`, `legends`,
`chronos` et `dioramas`, il n'est **pas** écrit atomiquement. Un crash en pleine écriture peut
le tronquer, et il contient le champion courant. S'il échoue à l'analyse tu obtiens
`[CobbleCap] Failed to load tenures: …` et une liste de règnes vide, ce qui signifie aucun
champion courant et un tableau compétitif blanc. Restaure-le depuis une sauvegarde ; il n'y a
aucune récupération automatique.

## Une statue ou une légende de test ne veut pas disparaître

**Cause.** `test_clear` ne touche que les données de l'invocateur.

**Remède.** `/cobblecap statue test_clear` retire les statues marquées test et les entrées de
légende de test **du joueur qui la lance**. Pour effacer celles de quelqu'un d'autre, utilise
`/cobblecap chrono wipe <player>` — mais noter que celle-là retire **toutes** les entrées de
légende de ce joueur, de test comme réelles, et annule son chrono. Il n'y a pas d'effacement
par joueur limité aux tests.

---

## Produire un rapport de bug utile

Ouvre un ticket sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **La version du jar**, depuis `gradle.properties` (`mod_version`) ou le nom de fichier.
2. **Les versions de Minecraft, Fabric Loader, Fabric API, Cobblemon et rctmod.** Les deux
   dernières comptent plus que tout le reste ici, parce que presque chaque mode de panne de ce
   mod est une cible de réflexion qui a bougé.
3. **De quel côté.** Serveur, client, ou les deux — et si le joueur qui rapporte a le mod
   installé.
4. **Le log concerné.** Serveur : `logs/latest.log`. Client : le `logs/latest.log` de
   l'instance. Cherche `[CobbleCap]` et inclus **tout** depuis le démarrage, pas seulement les
   lignes autour de la panne — les quatre lignes d'enregistrement des hooks au démarrage sont
   souvent la vraie réponse.
5. **La sortie de `/cobblecap status <player>`** pour le joueur concerné, en texte. C'est
   long ; colle-la quand même, ou joins-la. Elle contient la limite, la liste des champions
   battus, chaque condition avec `OK`/`FAIL`, chaque valeur de scoreboard et chaque
   advancement accordé.
6. **Les configurations.** `config/cobblecap/config.json` et
   `config/cobblecap/champions.json`, en masquant `discordWebhookUrl`.
7. **Pour un problème de morph**, la ligne `Datapack root resolved` ou
   `not found in any of` telle quelle, et le nom réel de ton répertoire de datapack.
8. **Pour un problème de statue**, `/cobblecap statue list` et toutes les lignes
   `[CobbleCap][Doppel]`.

Les questions plutôt que les bugs → [le Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CobbleCap](../README.fr.md) →
