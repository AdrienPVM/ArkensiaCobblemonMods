[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](statues.md)

# Statues et dioramas

Un diorama est un tableau figé : le Maître de la Ligue debout devant les six Pokémon avec
lesquels il a gagné. Il est construit avec des entités vanilla et des entités Cobblemon,
sans aucun type d'entité personnalisé ni modèle personnalisé, et c'est ce qui le rend
fonctionnel sur un client vanilla.

Toutes les commandes vivent sous `/cobblecap statue …` et sont documentées dans
[commandes statues, chrono et champion](commands-statues.fr.md). Cette page explique ce que
ces commandes construisent.

## De quoi est fait un diorama

| Figure | Entité | Comment elle est rendue visible |
|---|---|---|
| le dresseur | un **support d'armure marqueur invisible** (`ArmorStandEntity`) | une « doublure » de faux joueur superposée |
| chaque Pokémon | une vraie `PokemonEntity` Cobblemon | créée en dispatchant `/spawnpokemonat`, puis figée |

Le support d'armure n'est pas la statue — c'est le tracker. Il est créé avec
`setInvisible(true)` et `Marker: true` dans son NBT, sans gravité, invulnérable, silencieux,
bras affichés, socle masqué. Il existe pour que les commandes de déplacement, rotation,
échelle et style aient quelque chose à adresser, et pour que `ENTITY_LOAD` ait quelque chose
sur quoi se déclencher.

La raison de cette séparation est écrite dans le source : équiper une tête de joueur sur un
support d'armure visible donne une tête vissée sur un corps de support d'armure. L'exigence
était un corps de joueur complet, avec bras, jambes et proportions réelles, et la seule
façon d'obtenir cela sur un client vanilla est de lui envoyer une véritable entité joueur.

### La doublure

`ChampionDoppelganger` est un faux joueur — une entité côté serveur en forme de joueur avec
un vrai `GameProfile` — créée par-dessus le tracker et montrée à chaque spectateur
individuellement par paquets. `MojangProfileFetcher.resolve` construit ce profil par une
échelle à quatre niveaux : le joueur en ligne, puis le cache utilisateur du serveur, puis un
cache de session, puis une requête HTTP directe à Mojang. C'est ce dernier niveau qui permet
d'afficher la skin d'un champion **hors ligne**, ce qui est le cas normal pour une statue.

Sans profil porteur de textures, la statue n'apparaît tout simplement pas :

```text
[CobbleCap] No profile with textures for Steve (f84c6a79-…) — doppel will not render
```

Garder un faux joueur visible demande un vrai travail, et `StatueManager.init` enregistre
cinq écouteurs pour cela :

| Événement | Pourquoi |
|---|---|
| `ServerPlayConnectionEvents.JOIN` | chemin rapide, pour qu'un joueur apparaissant à portée la voie immédiatement |
| `ServerPlayConnectionEvents.DISCONNECT` | oublier le spectateur pour qu'une reconnexion renvoie l'apparition |
| `ServerEntityEvents.ENTITY_LOAD` | rattacher la doublure quand un chunk longtemps déchargé revient |
| `ServerEntityEvents.ENTITY_UNLOAD` | la retirer |
| `EntityTrackingEvents.START_TRACKING` / `STOP_TRACKING` | le vrai filet de sécurité — Minecraft retire un faux joueur quand le spectateur sort de la portée de suivi **d'entité**, plus courte que la portée de chunk, et ne le renvoie jamais |

En plus de cela, `tick()` lance une auto-réparation toutes les **60 ticks** (3 secondes) en
appelant `ensureVisibleToNearby(128.0)` sur chaque doublure, avec 128 blocs choisis comme
marge au-delà du rayon de suivi vanilla de 160 blocs. Le source est franc sur la raison :
JOIN et `START_TRACKING` ne livrent pas toujours, l'égalité de référence des mondes a
dérivé lors de rechargements, et les supports d'armure marqueurs ne propagent pas
`START_TRACKING` de façon fiable. C'est l'auto-réparation qui maintient réellement les
statues visibles ; les événements sont des optimisations.

Il y a aussi une étape explicite de **dédoublonnage**. Deux instances de
`ServerPlayerEntity` partageant un UUID embrouillent le cache de textures de skin du client
et on obtient le repli Steve ; avant d'enregistrer une nouvelle doublure, toute autre
partageant le même UUID synthétique est masquée et abandonnée :

```text
[CobbleCap][Doppel] DEDUP: removing stale doppel for syntheticUuid=… (old anchor=…, new anchor=…)
```

Au démarrage, `rebindDoppelsOnBoot` parcourt tous les mondes à la recherche des trackers de
l'emplacement joueur et rattache une doublure à chacun. Les supports d'armure survivent à un
redémarrage ; leurs doublures non, donc sans cette passe chaque statue reviendrait
décapitée après un redémarrage.

### Les Pokémon

`spawnPokemonStatue` ne construit pas d'entité. Il bâtit une chaîne de propriétés Cobblemon
depuis le `TeamMember` stocké et dispatche une commande :

```text
spawnpokemonat 128 71 -344 garchomp level=78 shiny mega
```

L'espèce d'abord, puis `level=` s'il est non nul, puis `shiny`, puis l'aspect `form`, puis
tous les autres aspects sauf les doublons de la forme et `shiny`. Les coordonnées sont des
**entiers** — `/spawnpokemonat` prend un `BlockPosArgument` et tronquerait les décimales de
toute façon.

Deux arêtes vives, toutes deux gérées :

- La commande de spawn de Cobblemon 1.6.1 appelle `source.getPlayer().getUuid()` et lève une
  NPE si la source n'a pas de joueur. La commande est donc exécutée sur
  `caster.getCommandSource()` — liée à un joueur — et la méthode **échoue immédiatement**,
  en renvoyant `false` sans rien dispatcher, quand il n'y a pas d'invocateur valide. C'est
  pourquoi un rafraîchissement cherche n'importe quel opérateur en ligne (`findOnlineOp`)
  pour l'emprunter comme mandataire, et pourquoi les dioramas `place_current` ne se
  rafraîchissent pas tant qu'aucun opérateur n'est connecté.
- La commande ne renvoie aucune entité. Le nouveau Pokémon est retrouvé par **heuristique** :
  on relève tous les UUID d'entités Cobblemon du monde avant le dispatch, puis on cherche
  ensuite un Pokémon Cobblemon non étiqueté dans un rayon de 4 blocs autour de la position
  visée, le plus proche d'abord. Sur un serveur chargé avec des Pokémon qui apparaissent à
  proximité, cela peut en principe capter la mauvaise entité. En pratique le filtre
  « non étiqueté et à moins de 4 blocs » rend cela rare, mais c'est une heuristique et non
  une garantie.

Un membre d'équipe dont `species` est vide est ignoré, et `placeFullDiorama` indique
lesquels ont échoué :

```text
spawned 5 entit(y/ies) — failed: zacian
```

## Disposition

`placeFullDiorama` utilise la position et le lacet exacts de l'invocateur :

- le dresseur se tient là où l'invocateur se tenait, tourné dans la même direction
- les Pokémon forment une rangée **3,5 blocs derrière** le dresseur, espacés de 2 blocs,
  centrés sur son axe

Avec moins de six Pokémon la rangée reste centrée. L'échelle `scale` s'applique à tout ce
qui est placé dans cet appel.

Il n'y a aucun contrôle de collision et aucun alignement au sol. Le dresseur apparaît à la
position des pieds de l'invocateur : se tenir sur une dalle ou en plein saut y place aussi
la statue. Place depuis une position debout sur terrain plat, puis ajuste avec `move`.

## Les étiquettes

Chaque entité d'un diorama porte des étiquettes de commande, et ces étiquettes sont le seul
index — il n'existe aucun registre des statues placées sur le disque.
`/cobblecap statue list` fonctionne en parcourant les mondes à la recherche d'étiquettes.

| Étiquette | Signification |
|---|---|
| `cobblecap_statue` | l'étiquette maîtresse ; tout dans le namespace la porte |
| `cobblecap_statue_test` | créé par `/cobblecap statue test` |
| `cobblecap_statue_current` | fait partie d'un diorama dynamique du champion courant |
| `cobblecap_statue_legend.<nom>` | la légende à laquelle appartient cette figure, **en minuscules** |
| `cobblecap_statue_slot.<slot>` | `player`, ou `0`–`5` pour les emplacements d'équipe |
| `cobblecap_statue_style.<clé>` | le style courant |

Deux conséquences du choix du **nom** de joueur en minuscules comme clé : renommer un
joueur orpheline son diorama, et deux légendes dont les noms ne diffèrent que par la casse
entrent en collision.

## Les styles

Un style, c'est trois choses : une couleur de lueur, une aura de particules et un ensemble
d'armure cosmétique.

`ensureTeams` crée une équipe de scoreboard vanilla par style, nommée `cgcap_<clé>`, au
démarrage du serveur. La couleur du contour lumineux vient de l'équipe, parce que la lueur
vanilla utilise une couleur `Formatting` nommée plutôt que du RVB brut —
`closestFormatting` choisit la couleur nommée la plus proche de la valeur hexadécimale du
style dans l'espace RVB. `natural` n'a pas de lueur.

`tick()` tourne tous les **10 ticks** et, pour chaque entité portant l'étiquette maîtresse :

1. réapplique un effet `GLOWING` de 30 ticks quand le style brille, pour que le contour ne
   s'éteigne jamais
2. fait apparaître 3 particules à 60 % de la hauteur de l'entité, dispersées selon sa
   largeur et sa hauteur
3. remet l'entité dans la bonne équipe si elle a dérivé

C'est un parcours complet de toutes les entités de tous les mondes chargés, une fois toutes
les 10 ticks. C'est peu coûteux dans le cas général parce que le test d'étiquette vient en
premier, mais cela reste un parcours.

Les ensembles d'armure de `equipStyleArmor` sont appliqués au **support d'armure**, qui est
invisible, donc en pratique ils ne sont pas visibles sur une statue de joueur :

| Style | Armure |
|---|---|
| `stone` | fer |
| `gold` | or |
| `emerald`, `diamond`, `amethyst`, `crystal` | diamant |
| `copper` | mailles |
| `netherite`, `obsidian` | netherite |
| `frost` | cuir, sans bottes |
| `soul` | netherite, sans bottes |
| `glass` | aucune, et `setInvisible(true)` |
| `natural` | aucune |

⚠️ `equipStyleArmor` n'a **aucun appelant** dans `StatueManager`. La méthode est du code
mort, héritée de l'ancienne conception à support d'armure visible que l'approche par
doublure a remplacée. L'effet visible d'un style aujourd'hui est la couleur de lueur et les
particules, rien d'autre.

## Le diorama dynamique du champion

`place_current` et `place_current_player` construisent un diorama lié à un rôle plutôt qu'à
une personne. La position est stockée comme un `DioramaAnchor` dans
`config/cobblecap_dioramas.json` :

```json
{
  "anchors": [
    {
      "worldId": "minecraft:overworld",
      "x": 128.5,
      "y": 71.0,
      "z": -340.5,
      "yaw": 180.0,
      "styleKey": "gold",
      "scale": 1.0
    }
  ]
}
```

`playerOnly` n'est écrit que s'il est vrai, et `placeholderLegend` contient un `LegendEntry`
complet sérialisé quand un bouchon est en usage. Écrit de façon atomique via un voisin
`.tmp`.

### Qui est « le champion courant »

`currentChampionEntry()` résout le champion **compétitif** — le règne unique ouvert dans
`TenureStorage`. Les Maîtres de la Ligue classiques n'ont volontairement aucun diorama
dynamique ; ils ont une entrée au Panthéon et un record de chrono.

À partir du `championUuid` de ce règne, il suit une échelle à trois barreaux, parce que
`LegendsStorage` et `TenureStorage` peuvent se contredire :

1. l'entrée non-test la plus récente pour cet UUID avec `mode == RANKED`
2. à défaut, l'entrée non-test la plus récente pour cet UUID quel que soit son mode — bonne
   identité, bonne équipe
3. à défaut, une entrée synthétisée depuis le seul `Tenure` — bon UUID et bon nom, donc la
   skin est correcte, mais **aucune équipe**, donc aucune rangée de Pokémon

### Le bouchon

Sans champion compétitif encore couronné, `place_current` ne refuse pas. Il fige l'équipe de
**l'invocateur** et l'utilise comme bouchon, et ce bouchon est sérialisé dans l'ancre pour
qu'un corps visible survive aux redémarrages. La réponse le dit :

```text
[CURRENT] PLACEHOLDER (no champion yet — using your profile; auto-replaces when first champion is crowned) — spawned 7 entit(y/ies)
```

Le premier vrai champion le remplace, et le bouchon est alors retiré de l'ancre pour que les
instantanés périmés ne s'accumulent pas dans le fichier.

### Quand cela se rafraîchit

`refreshCurrentChampionDioramas` efface chaque entité `cobblecap_statue_current` de tous les
mondes et refait apparaître un diorama par ancre. Il est appelé depuis :

- `RctTrainerMorpher.dispatchReload`, à la fin de chaque flush de morph
- `RctTrainerMorpher.restoreOriginal`
- `StatueManager.init`, juste après le démarrage
- `/cobblecap champion refresh_dioramas`

L'effacement-recréation a lieu même en mode bouchon, parce que la passe de rattachement au
démarrage ne peut pas résoudre un invocateur bouchon qui n'a aucune entrée dans
`LegendsStorage`.

⚠️ Le rafraîchissement est couplé au morph, et le morph est abandonné quand le datapack
n'est pas trouvé (voir
[le morphing du champion](champion-morphing.fr.md#ce-que-tu-dois-changer)). Sur un serveur
où `datapackName` est encore `CacademyMons`, les dioramas dynamiques ne se rafraîchissent
jamais à une victoire — seuls le rafraîchissement au démarrage et la commande manuelle
fonctionnent.

## Le mode test

`/cobblecap statue test` écrit un `LegendEntry` avec `isTest: true` et met en scène un
diorama à partir de l'équipe actuelle de l'invocateur. Les entrées de test sont invisibles
pour le public : exclues de `getAll()`, `hasLegend()`, `isEmpty()`, `/cobblecap legends`, de
toutes les annonces, du webhook Discord et des scoreboards Conseil 4. Seuls
`getAllIncludingTests()` — utilisé par le sous-système de statues — et
`findLatestForPlayerName(nom, includeTests = true)` les voient.

Noter l'asymétrie dans les recherches qui incluent les tests :

| Appelant | Inclut les tests |
|---|---|
| `statue place` / `place_player` / `place_pokemon` | **non** — `includeTests = false` |
| les chemins de rattachement des doublures (`ENTITY_LOAD`, `START_TRACKING`, démarrage) | **oui** |
| `/cobblecap statue test` | en crée un |

Un diorama de test s'affiche donc et survit aux redémarrages, mais tu ne peux pas le
replacer par son nom avec `statue place` — seul `statue test` le crée. Nettoie avec
`/cobblecap statue test_clear`, qui ne retire que les statues de test et les entrées de test
de l'invocateur lui-même.

## Nettoyage

| Commande | Portée |
|---|---|
| `statue remove_slot` | une figure |
| `statue remove` | tout le diorama d'une légende |
| `statue test_clear` | les statues et entrées de test de l'invocateur |
| `statue wipe_all` | ⚠️ toutes les entités de statue de tous les mondes, plus la liste d'ancres |

`wipe_all` est basé sur les étiquettes plutôt que sur les ancres, et c'est bien l'intérêt :
il attrape les supports d'armure invisibles orphelins dont la doublure ne s'est jamais
rattachée, et ceux-là sont invisibles et donc impossibles à retrouver à la main. Il vide
aussi `currentChampionAnchors`, donc `place_current` repart de zéro après coup.

Chaque suppression passe par `despawnWithDoppel`, qui masque la doublure avant d'éliminer le
tracker. Éliminer un tracker sans cela laisserait un faux joueur visible pour tous ceux à
qui il avait déjà été envoyé, sans plus rien pour le retirer.

## Faiblesses connues

- **Les étiquettes sont le seul index.** Une statue dont les étiquettes ont été retirées —
  par `/tag remove`, ou par une édition de monde qui copie des entités — devient un orphelin
  que seul `wipe_all` peut retrouver.
- **Indexé par nom de joueur.** Les changements de pseudo orphelinent les dioramas.
- **L'apparition des Pokémon est une heuristique.** Aucune API ne renvoie l'entité créée,
  donc elle est localisée dans l'espace.
- **Le rafraîchissement a besoin d'un opérateur en ligne.** `findOnlineOp` renvoie `null`
  quand personne de niveau 2 n'est connecté, et la rangée de Pokémon est alors ignorée pour
  ce rafraîchissement.
- **Du code mort.** `equipStyleArmor` n'est jamais appelée.
- **Le tick de style parcourt toutes les entités de tous les mondes** toutes les 10 ticks.

---

← [Le morphing du champion](champion-morphing.fr.md) · [Le côté client](client.fr.md) →
