[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CheesePatrol est **uniquement côté serveur**. `fabric.mod.json` déclare
`"environment": "server"`, et il n'y a ni entrypoint client, ni mixin client, ni payload
personnalisé. Les joueurs qui se connectent à un serveur équipé n'ont rien à installer :
ils voient les marqueurs en porte-armures et les particules comme n'importe quel autre
effet envoyé par le serveur.

---

## Prérequis

| | Version | Pourquoi |
|---|---|---|
| Minecraft | `~1.21.1` | déclaré dans `depends` |
| Fabric Loader | `≥ 0.16.0` | déclaré dans `depends` |
| Fabric API | quelconque | events, commandes, networking, items |
| Java | `≥ 21` | `options.release = 21` dans `build.gradle` |
| [CheeseDash](../../CheeseDash/) | quelconque | **dépendance stricte** — `"cheesedash": "*"` dans `depends` |
| [EasyNPC](https://modrinth.com/mod/easy-npc) | 6.2.0 testée | **suggérée**, pas exigée par le loader |

CheeseDash est une entrée `depends`, pas un `suggests` : le loader refuse de démarrer sans
lui. C'est plus strict que ce que le code exige — la logique de patrouille elle-même
n'appelle jamais CheeseDash — mais c'est ce que dit le manifeste, donc installe CheeseDash.

EasyNPC, c'est l'inverse. Il est dans `suggests`, le loader démarre sans problème sans lui,
et le mod te laissera même attacher une patrouille à un zombie vanilla. Ce qu'EasyNPC
apporte, c'est la méthode `getEntityGoalSelector()` sur laquelle CheesePatrol réfléchit
pour effacer les goals d'inactivité ; sans goal selector à nettoyer, un NPC venu d'un
autre mod peut refuser d'avancer. Voir
[la dépendance EasyNPC](architecture.fr.md#la-dépendance-easynpc-tient-en-un-appel-par-réflexion).

## Ordre de build

CheesePatrol compile contre le jar construit de CheeseDash, résolu par un glob sur
`../CheeseDash/build/libs/cheesedash-*.jar`. CheeseDash doit donc être **construit
d'abord** — il ne suffit pas que son dossier source soit présent.

```bash
cd CheeseDash      && ./gradlew build
cd ../CheesePatrol && ./gradlew build
```

`tools/build-all.sh`, à la racine du dépôt, parcourt tous les mods dans l'ordre des
dépendances : c'est la voie la plus simple si tu construis toute la suite.

Le jar d'EasyNPC est récupéré depuis le Maven Modrinth sous
`maven.modrinth:easy-npc:PG3ZdKaC`, donc un clone propre n'exige aucun téléchargement
manuel. C'est une dépendance `compileOnly` et rien n'en est embarqué.

Le résultat est `CheesePatrol/build/libs/cheesepatrol-1.0.0+1.21.1.jar`. La version vient
de `mod_version` dans `gradle.properties` ; un `-sources.jar` est également produit et ne
doit pas être déployé.

## Installation sur un serveur

À copier dans le `mods/` du serveur :

- `cheesepatrol-1.0.0+1.21.1.jar`
- le jar de CheeseDash
- le jar d'EasyNPC

Rien d'autre. Il n'y a aucun fichier de configuration à préparer.

## Ce que crée le premier démarrage

Rien. Cela mérite d'être dit, parce que tous les mods voisins écrivent un fichier de
configuration au premier démarrage et que CheesePatrol n'en écrit pas.
`PatrolManager.load()` lit `config/cheesepatrol_data.json` **s'il existe** et reste muet
sinon ; le fichier n'est écrit que par `PatrolManager.save()`, qui tourne la première fois
que tu fais un clic droit sur un NPC avec la baguette, ajoutes un point de passage,
modifies un réglage, démarres ou arrêtes une patrouille — et, sans condition, à chaque
`SERVER_STOPPING`. Le premier arrêt propre écrit donc le fichier même si tu n'as jamais
touché à une patrouille : `{"debug": false, "patrols": []}`.

Une fois une patrouille créée, deux choses existent :

```text
config/
├── cheesepatrol_data.json          toutes les patrouilles : points de passage, vitesse, rayon, état
└── cheesepatrol_entities/
    └── <npc-uuid>.dat              instantané NBT compressé de l'entité NPC
```

Les fichiers `.dat` apparaissent à la sauvegarde périodique suivante — elle tourne toutes
les 60 secondes, comptées depuis le démarrage du serveur, et couvre chaque patrouille dont
le NPC est chargé à ce moment-là — et à l'arrêt du serveur. Les deux sont
documentés dans [configuration](configuration.fr.md) et
[garder les NPC en vie](resilience.fr.md).

Un troisième effet de bord, invisible : les chunks force-loadés. CheesePatrol force-load le
chunk dans lequel se tient chaque NPC et celui de son point de passage cible, et **ne les
relâche délibérément pas à l'arrêt**, pour que l'entité soit encore chargée au démarrage
suivant. Ce sont de vrais chunks forcés vanilla, stockés dans les données du monde, et
`/forceload query` les listera. Raisonnement complet dans
[garder les NPC en vie](resilience.fr.md#le-force-load-des-chunks).

## Vérifier le chargement

Quatre lignes de log, dans cet ordre :

```text
[CheesePatrol] Initializing...
[CheesePatrol] Ready.
[CheesePatrol] Early-loaded 0 patrol(s) for mixin protection
[CheesePatrol] Forceloaded chunks for 0 patrol(s) (0 active, 0 inactive)
```

Les deux premières viennent de `onInitialize`, pendant le chargement des mods. La
troisième vient de l'événement `SERVER_STARTING`, ce qui explique qu'elle apparaisse après
`Ready.` alors qu'elle a lieu plus tard. Le compteur est le nombre de patrouilles lues
dans `cheesepatrol_data.json`. La quatrième vient de `SERVER_STARTED` et s'affiche à
chaque démarrage, même sans aucune patrouille.

Dès que tu as des patrouilles, la sortie de `SERVER_STARTED` peut passer à deux lignes :

```text
[CheesePatrol] Pre-loaded 7 chunks for 3 patrol(s)
[CheesePatrol] Forceloaded chunks for 3 patrol(s) (2 active, 1 inactive)
```

`Pre-loaded` n'apparaît que si au moins un chunk a réellement dû être forcé ; si tous
l'étaient déjà depuis la session précédente, la ligne est sautée et seul
`Forceloaded chunks for …` s'affiche.

En jeu, vérifie deux choses :

1. `/npcpatrol list` répond. Si la commande ne se complète pas par tabulation, tu es sous
   le niveau de permission 2 ou le mod ne s'est pas chargé.
2. `/npcpatrol wand` met une **Patrol Wand** dorée et scintillante dans ton inventaire.
   C'est une tige de Blaze portant le tag de données personnalisées `cheesepatrol_wand` ;
   une tige de Blaze ordinaire ne fonctionnera pas.

Côté dashboard, ouvre CheeseDash et cherche **Patrols** dans la barre latérale, dans la
section CheesePatrol. La vue est enregistrée via l'entrypoint `cheesedash` déclaré dans
`fabric.mod.json` ; si elle manque, voir
[dépannage](troubleshooting.fr.md#la-vue-patrols-manque-dans-la-barre-latérale-du-dashboard).

## Désinstaller proprement

L'ordre compte ici plus que pour la plupart des mods, parce que le mixin qui bloque les
suppressions et les chunks forcés survivent tous les deux à une simple suppression du jar.

1. **Arrête et efface chaque patrouille d'abord**, pendant que le mod est encore installé :

   ```mcfunction
   /npcpatrol list
   ```

   Puis, pour chacune, sélectionne son NPC (baguette en main, clic droit dessus ou
   `/npcpatrol select <uuid>`) et lance :

   ```mcfunction
   /npcpatrol clear
   ```

   `clear` relâche les chunks forcés de cette patrouille, supprime ses marqueurs en
   porte-armures dans ton monde courant, la retire du fichier de données et efface sa
   sauvegarde NBT. Cela lève aussi la protection contre la suppression : à partir de là, le
   NPC peut être tué ou supprimé normalement.

2. **Occupe-toi des NPC.** Ce sont de simples entités EasyNPC auxquelles `setPersistent()`
   a été appliqué. Laisse-les en place ou passe-les au `/kill` — à toi de voir, mais
   fais-le avant de retirer le jar, puisqu'après ce ne seront plus que des NPC sans
   patrouille.

3. **Nettoie les marqueurs restants** s'il en survit. Ce sont des porte-armures invisibles
   tagués `cheesepatrol` :

   ```mcfunction
   /kill @e[type=armor_stand,tag=cheesepatrol]
   ```

   Un arrêt propre supprime tous ceux qui se trouvent dans un chunk chargé, dans tous les
   mondes : ceci n'est donc nécessaire qu'après un crash, ou pour les marqueurs restés dans
   des chunks qui n'étaient pas chargés au moment de l'arrêt.

4. **Arrête le serveur, supprime le jar**, puis supprime ce dont tu ne veux plus :

   ```text
   config/cheesepatrol_data.json
   config/cheesepatrol_entities/
   ```

   Les laisser est sans conséquence — rien d'autre ne les lit — mais ils seront repris si
   tu réinstalles un jour.

5. **Vérifie les chunks forcés orphelins.** Si tu as sauté l'étape 1, les chunks forcés
   pour les NPC de patrouille restent forcés à jamais, puisque le mod n'est plus là pour
   les relâcher :

   ```mcfunction
   /forceload query
   /forceload remove <x> <z>
   ```

Ce qui reste après tout cela : les entités NPC elles-mêmes (si tu les as gardées), et rien
d'autre.

---

← [CheesePatrol](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
