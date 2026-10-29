[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Une section par symptôme, chacune avec sa cause et son remède. La plupart des problèmes de
CobbleShift viennent de trois endroits : un client sans le mod, une recherche par réflexion
dans Cobblemon qui ne correspond plus, et une clé de config qui ne fait pas ce que dit son
nom. Quand un symptôme n'est pas listé, commence par le journal du serveur, avec les lignes
décrites dans [rédiger un rapport de bug utile](#rédiger-un-rapport-de-bug-utile).

---

## Le mod ne se charge pas

**Cause.** Fabric refuse le jar avant que CobbleShift n'exécute la moindre ligne. Les
raisons habituelles : une version de Minecraft autre que `1.21.1`, un Fabric Loader plus
ancien que `0.16.0`, un Java antérieur à 21, ou Fabric API absent. L'écran de crash ou
`logs/latest.log` nomme l'exigence non satisfaite.

**Remède.** Respecte les [prérequis](installation.fr.md#prérequis). Si le jar s'est chargé,
le journal du serveur contient, dans cet ordre :

```text
[CobbleShift] Initializing...
[CobbleShift] TypeProjectileEntity registered
[CobbleShift] Initialized successfully!
```

L'absence de la ligne `Initializing...` signifie que Fabric n'a jamais atteint
l'entrypoint. Une ligne `Failed to load TypeProjectileEntity!` à la place de la deuxième
signifie que la classe manque dans le jar, en général à cause d'une compilation ratée.

## Toutes les transformations échouent avec « espèce introuvable »

```text
Échec : espèce 'pikachu' introuvable ou Cobblemon non disponible
```

**Cause.** `SpeciesMapper.resolve()` n'a rien renvoyé. Trois cas se ressemblent vus depuis
la commande :

1. **Cobblemon n'est pas installé.** Il n'est que suggéré (`suggests`) dans
   `fabric.mod.json` : le mod se charge sans lui et refuse toutes les espèces. Le journal
   de démarrage indique `Cobblemon not found — using fallback stats` ; ignore la seconde
   moitié, il n'existe aucun repli.
2. **Le nom est faux.** La recherche essaie le nom en minuscules, puis sans tirets, puis
   avec des tirets bas. Tout le reste échoue, par exemple un nom affiché qui contient une
   espace.
3. **Cobblemon a changé son API.** `PokemonSpecies.INSTANCE`, `getByName`, `getBaseStats` ou
   `getPrimaryType` ne correspondent plus. Les échecs à l'intérieur de `resolve()` ne sont
   journalisés qu'au niveau **debug**, sous la forme
   `Failed to resolve species '<name>': <message>`.

**Remède.** Lance `/cshift species pikachu`. Si Cobblemon est installé et que même Pikachu
échoue, active le niveau debug pour le logger `cobbleshift` et cherche cette ligne : son
message nomme la méthode introuvable. C'est le cas 3, et il demande une correction du code
de `SpeciesMapper`, voir
[la réflexion](architecture.fr.md#la-réflexion-et-pourquoi-les-noms-de-méthode-comptent).

## La commande semble ne rien faire

`/cshift transform Steve charizard` rend la main sans message de succès ni erreur.

**Cause.** `transformCooldownEnabled` est activé et Steve a été ramené à sa forme normale
il y a moins de `transformCooldownSeconds`. Le refus est adressé à **Steve**, dans sa barre
d'action :

```text
⛔ Cooldown : encore 42s avant de pouvoir se transformer.
```

L'appelant ne voit rien. Un appelant du dashboard reçoit à la place l'erreur générique
« espèce introuvable ».

**Remède.** Attends, ou transforme Steve pendant qu'il est encore transformé : changer
d'espèce saute le cooldown. Pour supprimer le cooldown, passe `transformCooldownEnabled` à
`false` via `PUT /api/v1/shift/config`.

## Un joueur transformé est invisible

Les autres joueurs ne voient personne à l'endroit où se tient Steve.

**Cause.** Le client de l'observateur n'a pas CobbleShift. Le serveur rend un joueur
transformé invisible et compte sur le moteur de rendu de chaque client pour dessiner le
Pokémon. Un client vanilla reçoit l'Invisibilité et jamais le modèle.

**Remède.** Installe le même jar sur tous les clients. Sur un serveur qui synchronise ses
mods vers les clients (AutoModPack ou équivalent), vérifie que CobbleShift fait partie de
l'ensemble synchronisé.

Si l'observateur **a** le mod et ne voit toujours rien, le fantôme n'a pas pu être
construit. Le journal du client ne contient alors pas la ligne
`[CobbleShift] Ghost ready: '<species>' …` pour cette espèce. La cause probable est une mise
à jour de Cobblemon qui a modifié `PokemonEntity` ou `PokemonProperties`.

## Un joueur est invisible et n'a pas la bonne taille après un crash

**Cause.** Le serveur a planté pendant que ce joueur était transformé. La liste des
transformations ne vit qu'en mémoire : elle a disparu, mais les trois modificateurs
d'attributs persistants et l'Invisibilité infinie sont toujours dans le fichier du joueur.

**Remède.** Fais reconnecter le joueur avec CobbleShift installé. À une connexion sans
transformation active, `cleanupStaleTransform()` retire les modificateurs et l'Invisibilité,
et journalise :

```text
[CobbleShift] Cleaned up stale transform modifiers for Steve
```

Si le mod a été retiré entre-temps, les commandes pour nettoyer à la main sont dans
[désinstaller](installation.fr.md#désinstaller).

## Un joueur transformé qui meurt reste transformé

**Cause.** `autoRevertOnDeath` vaut `true` par défaut et ne fait rien : aucun événement de
mort n'appelle `TransformManager.onPlayerDeath()`.

**Remède.** Annule toi-même la transformation des joueurs morts avec
`/cshift revert <player>`, ou depuis ce qui les a tués (l'effet `REVERT_ALL` d'un événement
CheeseGuard, par exemple). Une correction du code est décrite dans
[l'architecture](architecture.fr.md#points-dextension).

## Un changement de config reste sans effet

**Cause.** L'une de ces quatre raisons :

1. **Tu as modifié le fichier pendant que le serveur tournait.** Le fichier n'est lu
   qu'une fois, au démarrage. Pire, le prochain `/cshift ability global …` ou la prochaine
   sauvegarde depuis le dashboard réécrit par-dessus les valeurs de démarrage.
2. **La clé ne fait rien.** `autoRevertOnDeath`, `hotPotatoEnabled` et `hotPotatoPassRange`
   ne sont jamais lues. Voir [les clés qui ne font rien](configuration.fr.md#clés-qui-ne-font-rien).
3. **La clé a été bornée.** Via le dashboard, `abilityTickInterval: 5` devient `20` sans un
   mot. Voir [plages et bornes](configuration.fr.md#plages-et-bornes).
4. **Une faute de frappe a cassé le chargement.** Un mauvais type lève une exception, et le
   journal affiche `Failed to load config, using defaults: <message>`. Les clés situées
   après la mauvaise sont restées à leur valeur par défaut.

**Remède.** Modifie le fichier serveur arrêté, ou utilise `PUT /api/v1/shift/config` et
relis le résultat avec `GET /config`.

## Les capacités passives ne font rien

**Cause.** Par ordre de probabilité :

1. `abilityGlobalEnabled` vaut `false`, sa valeur par défaut.
2. Le joueur est exclu par `/cshift ability toggle`. L'exclusion dure jusqu'au redémarrage.
3. Le Pokémon de tête du joueur est de type `poison` ou `normal`, qui n'accordent aucun
   effet.
4. Cobblemon a changé l'API des équipes. `getLeadPokemon()` ne renvoie alors rien, ou tous
   les types retombent sur `normal`, et les capacités n'accordent plus rien à personne, sans
   bruit.

**Remède.** `/cshift ability global enable`, puis `/cshift list`. Si
`Abilities actives : 0` reste à zéro alors que des joueurs portent des Pokémon, c'est le
cas 4 ; le chemin de lecture complet est dans
[les capacités passives](abilities.fr.md#lire-le-pokémon-de-tête).

## La Vision nocturne du type Ténèbres clignote

**Cause.** Le client fait clignoter la Vision nocturne pendant ses 200 derniers ticks, et
`abilityEffectDuration` vaut 160 par défaut. L'effet ne sort jamais de cette fenêtre. C'est
le rendu vanilla, observé plutôt que spécifié par CobbleShift.

**Remède.** Monte `abilityEffectDuration` au-dessus de 200, par exemple `260`, en la gardant
supérieure à `abilityTickInterval`.

## Le serveur plante dès son premier tick après une modification de la config

**Cause.** `abilityTickInterval` vaut `0` dans `cobbleshift.json`. `AbilityManager.tick()`
exécute `tickCounter % 0`, ce qui lève une `ArithmeticException`, avant même de vérifier si
les capacités sont activées. La valeur du fichier contourne le minimum de 20 du setter.

**Remède.** Remets une valeur positive, `100` par défaut, et redémarre.

## Les attaques semblent plus lentes que ne le dit la config

**Cause.** Le client refuse les clics pendant 30 ticks fixes après chaque attaque, quel que
soit le cooldown du serveur. Un `attackCooldownMultiplier` inférieur à `1.0` ne peut pas
rendre les attaques plus fréquentes qu'une toutes les 1,5 seconde. Avec de longs cooldowns
serveur, la barre du HUD affiche « prêt » avant le serveur, et le clic est perdu. Voir
[la configuration](configuration.fr.md#attaques).

**Remède.** Aucun par la config. Garde le multiplicateur à `1.0` ou légèrement au-dessus.
Une vraie correction suppose d'envoyer le cooldown du serveur au client.

## `/cshift` est refusé

**Cause.** Tout l'arbre exige le niveau de permission 2. En dessous, `/cshift` n'apparaît pas
dans la complétion et le taper donne l'erreur vanilla *Commande inconnue ou incomplète*.
Certaines sous-commandes échouent pour d'autres raisons :

- `transformnpc`, `revertnpc`, `scalenpc` et `speednpc` exigent un appelant **joueur** : la
  console et les blocs de commande reçoivent l'erreur vanilla indiquant qu'un joueur est
  requis.
- `/cshift ability <player> …` ne peut pas viser un joueur qui s'appelle littéralement
  `global` ou `toggle`, parce que Brigadier essaie les littéraux en premier.

**Remède.** `/op` sur le compte, ou le niveau 2 via ton mod de permissions. Lance les
sous-commandes PNJ en jeu.

## « Cette entité n'est pas un PNJ EasyNPC » alors que tu regardes un PNJ

**Cause.** Le lancer de rayon de 5 blocs prend la **boîte d'entité la plus proche** sur ta
ligne de visée et ignore les blocs. Tout ce qui se trouve entre toi et le PNJ, un Pokémon ou
un porte-armure, devient la cible. L'autre cause est un EasyNPC absent ou renommé : la
vérification porte sur l'espace de noms `easy_npc` du registre.

**Remède.** Approche-toi, sans rien entre vous deux. Vérifie que l'identifiant de l'entité
commence par `easy_npc:` (F3 avec le réticule dessus).

## Une transformation de PNJ a disparu après un redémarrage

**Cause.** L'une de ces raisons :

1. Le serveur a planté avant toute sauvegarde. Les transformations de PNJ sont sauvegardées
   à chaque modification : seule la dernière est perdue.
2. Le fichier de sauvegarde est ailleurs. Son chemin est relatif au dossier de travail, pas
   au dossier de configuration.
3. Une entrée du fichier était mal formée, le chargement s'est arrêté là, et la sauvegarde
   suivante n'a réécrit que les entrées lues avant elle. Le journal affiche
   `Failed to load NPC transforms: <message>`.

**Remède.** Cherche dans le journal de démarrage `Loaded <n> NPC transform(s) from
config/cobbleshift_npc_transforms.json`. Garde une copie de ce fichier avant de le modifier.
Détails dans [les transformations de PNJ](npc-transforms.fr.md#le-fichier-de-sauvegarde).

## Un PNJ marche vite après avoir été ramené à sa forme normale

**Cause.** Il a été annulé par `/cshift revertallnpc` alors que son chunk n'était pas
chargé. L'entrée a disparu, mais le modificateur persistant `cobbleshift:npc_speed` est
resté sur l'entité.

**Remède.** Charge le chunk et retire-le avec
`/attribute <npc> minecraft:generic.movement_speed modifier remove cobbleshift:npc_speed`.

## Le module CobbleShift n'apparaît pas dans le dashboard

**Cause.** CheeseDash n'est pas installé, n'a pas démarré, ou s'est chargé sans découvrir
l'entrypoint `cheesedash`. Pendant le démarrage du serveur, le module répond `503
{"error":"CobbleShift not ready"}`.

**Remède.** Vérifie que les deux jars sont dans `mods/` et que les lignes de démarrage de
CheeseDash sont présentes. Le module s'enregistre sous le préfixe `shift` : un test rapide
consiste à appeler `GET /api/v1/shift/config` avec un token de session valide. Les routes
sont dans [l'API du dashboard](dashboard.fr.md).

## Les modifications depuis le dashboard sont refusées en 403

**Cause.** Le rôle de la session est trop bas. La plupart des écritures demandent
`OPERATOR`. Deux demandent `SUPER_ADMIN` : `PUT /config` et `POST /ability/global`.

**Remède.** Connecte-toi avec un compte du bon rôle.

## Rédiger un rapport de bug utile

Ouvre un ticket sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues), ou pose d'abord
la question sur le [Discord Arkensia](https://discord.gg/fYmPwMBjdB). Joins :

- la version de CobbleShift (`mod_version`, par exemple `1.0.0+1.21.1`), celle de
  Cobblemon, et si EasyNPC et CheeseDash sont installés
- si le problème se situe sur le serveur, sur un client ou les deux, et si tous les clients
  ont le mod
- la commande ou l'appel REST exact, et ce que tu as vu
- dans le `logs/latest.log` du **serveur**, toutes les lignes contenant `[CobbleShift]`, à
  partir de `Initializing...`. Pour un problème d'espèce, ajoute les lignes d'un lancement
  avec le logger `cobbleshift` au niveau debug
- dans le `logs/latest.log` du **client**, les lignes `[CobbleShift]`, en particulier
  `Ghost ready: …` et `Water sync: …`, pour tout problème visuel
- `config/cobbleshift.json`, et `config/cobbleshift_npc_transforms.json` pour un problème de
  PNJ

Retire toute URL de webhook, tout token et toute adresse de ce que tu colles. Ces fichiers
et ces journaux viennent d'un serveur en production.

---

← [Architecture](architecture.fr.md) · [CobbleShift](../README.fr.md)
