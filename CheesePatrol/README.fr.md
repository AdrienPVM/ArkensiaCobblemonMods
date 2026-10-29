# CheesePatrol

> 🇬🇧 [Read in English](README.md)

> Circuits de patrouille pour entités EasyNPC : un NPC parcourt une boucle et s'arrête
> pour regarder les joueurs qui s'approchent.

EasyNPC te donne des NPC qui restent là où tu les as posés. Ce mod les fait marcher. Tu
traces un circuit à la baguette — clic droit sur le NPC, puis clic droit sur les blocs
qu'il doit visiter — tu règles une vitesse, et tu démarres. Le NPC enchaîne les points de
passage 1 → 2 → 3 → retour au 1, indéfiniment, et dès qu'un joueur entre dans son rayon
de détection il s'arrête net et se tourne vers lui jusqu'à ce qu'il s'éloigne.

La marche n'est pas celle du pathfinding d'EasyNPC. CheesePatrol écrase directement la
vélocité du mob à chaque tick et efface les goals d'IA d'EasyNPC toutes les cinq
secondes, parce que laisser ces goals en place empêche le NPC de bouger du tout. Le
déplacement est donc fiable et bête : le NPC va en ligne droite vers son prochain point
de passage et s'écrase contre une barrière plutôt que de la contourner. Place les points
de passage de façon qu'un NPC voie toujours le suivant.

Autour de ce cœur s'empile beaucoup de machinerie qui n'a qu'un but : un NPC de
patrouille ne doit jamais disparaître. Le mod se greffe sur `Entity.remove` pour bloquer
les kills et les despawns, maintient le chunk du NPC force-loadé, sauvegarde le NBT de
chaque NPC sur disque une fois par minute, et le fait réapparaître depuis cette
sauvegarde s'il s'évapore quand même. Cette machinerie a un coût, et
[architecture](docs/architecture.fr.md) ne le cache pas.

**Minecraft 1.21.1 · Fabric · Java 21 · 10 fichiers source, ~2 310 lignes · côté serveur**

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Obligatoire en pratique** | [EasyNPC](https://modrinth.com/mod/easy-npc) — ou tout mod dont les NPC héritent de `PathAwareEntity` |
| **Côté client** | rien. `environment` vaut `server` |

CheesePatrol n'importe jamais une classe d'EasyNPC. Il accepte n'importe quelle entité
héritant du `PathAwareEntity` vanilla, et n'atteint EasyNPC que par un unique appel par
réflexion cherchant une méthode `getEntityGoalSelector()`. ⚠️ Ce nom de méthode est
**écrit en dur** dans `PatrolManager.getEasyNPCGoalSelector` : quand EasyNPC le renommera
ou le supprimera, la recherche échouera en silence et les NPC cesseront d'avancer. Voir
[comment fonctionne la dépendance EasyNPC](docs/architecture.fr.md#la-dépendance-easynpc-tient-en-un-appel-par-réflexion).

## Installation

```bash
cd CheeseDash      && ./gradlew build     # CheesePatrol compile contre son jar
cd ../CheesePatrol && ./gradlew build
```

Dépose `build/libs/cheesepatrol-1.0.0+1.21.1.jar` et le jar de CheeseDash dans le dossier
`mods/` du serveur, à côté d'EasyNPC. Le mod n'écrit rien sur disque avant ta première
patrouille ou le premier arrêt du serveur, selon ce qui arrive en premier — le hook
d'arrêt sauvegarde toujours, donc le premier arrêt propre laisse un
`config/cheesepatrol_data.json` vide. Au démarrage il journalise :

```text
[CheesePatrol] Initializing...
[CheesePatrol] Ready.
[CheesePatrol] Early-loaded 0 patrol(s) for mixin protection
[CheesePatrol] Forceloaded chunks for 0 patrol(s) (0 active, 0 inactive)
```

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de build, ce que la première patrouille crée sur disque, les lignes de log qui prouvent le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge à un NPC qui parcourt vraiment un circuit de quatre points et s'arrête pour toi |
| [Commandes](docs/commands.fr.md) | Les 18 sous-commandes, argument par argument, avec niveaux de permission, sorties et tous les messages d'échec |
| [Configuration](docs/configuration.fr.md) | `cheesepatrol_data.json` en entier — il n'y a pas de fichier de réglages, seulement ce fichier d'état et un drapeau `debug` |
| [Circuits de patrouille](docs/patrol-circuits.fr.md) | Le modèle : l'ordre des points de passage, ce qui se passe au dernier, les seuils d'arrivée, les pauses, les segments en vol, l'arrêt « regarder un joueur », les deux chiens de garde anti-blocage |
| [Rendu visuel](docs/visuals.fr.md) | Aperçu du trajet en particules, marqueurs en porte-armures, et les 46 effets de traînée avec leurs décalages et leur portée |
| [Garder les NPC en vie](docs/resilience.fr.md) | Le mixin qui bloque les suppressions, le force-load des chunks, les sauvegardes NBT, l'échelle de réapparition et le contrôle d'intégrité |
| [API dashboard](docs/dashboard.fr.md) | Les deux routes REST sous `/api/v1/patrol`, les rôles requis, et pourquoi il n'y a aucun canal WebSocket |
| [Architecture](docs/architecture.fr.md) | Découpage des packages, responsabilités des classes, threading, le mixin, points d'extension, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — en commençant par les trois raisons pour lesquelles un NPC qui devrait marcher ne marche pas |

## Les commandes en un coup d'œil

Une seule commande, `/npcpatrol`, 18 sous-commandes, toutes au **niveau de permission 2**.
Toutes sauf `wand` et `list` lisent le NPC sélectionné dans la **Patrol Wand que tu
tiens** : la baguette doit donc être dans ta main principale.

| Commande | Effet |
|---|---|
| `/npcpatrol wand` | te donne une Patrol Wand (une tige de Blaze renommée) |
| `/npcpatrol select <uuid>` | sélectionne un NPC par son UUID au lieu de cliquer dessus |
| `/npcpatrol add [waitSeconds]` | ajoute un point de passage à tes pieds |
| `/npcpatrol add <x> <y> <z> [waitSeconds]` | ajoute un point de passage à des coordonnées |
| `/npcpatrol remove <index>` | supprime le point de passage `index` (compté depuis 1) |
| `/npcpatrol clear` | supprime toute la patrouille, ses marqueurs et sa sauvegarde |
| `/npcpatrol start` · `stop` | lance ou arrête le circuit |
| `/npcpatrol speed <value>` | vitesse de marche, `0.1`–`5.0`, défaut `0.5` |
| `/npcpatrol radius <value>` | rayon de détection des joueurs en blocs, `1.0`–`32.0`, défaut `5.0` |
| `/npcpatrol wait <index> <seconds>` | pause sur un point de passage, `0`–`3600` |
| `/npcpatrol list` · `info` | toutes les patrouilles, ou celle sélectionnée en détail |
| `/npcpatrol show` · `markers` | dessine le trajet en particules, ou plante des porte-armures nommés |
| `/npcpatrol particles <type>` | effet de traînée derrière le NPC, 46 valeurs |
| `/npcpatrol particleheight` · `particledepth` · `particlerange` | placer et limiter la traînée |

`wait` prend un **index de point de passage puis un nombre de secondes**, dans cet ordre —
l'ancien README de ce mod le décrivait comme un unique nombre de ticks. Détails dans
[commandes](docs/commands.fr.md).

## Dashboard

S'enregistre sous le préfixe `patrol`. La vue est un tableau en lecture seule de toutes
les patrouilles — nom du NPC, dimension, point de passage courant sur total, statut,
vitesse — avec une seule action : te téléporter au point de passage *qui suit* celui vers
lequel le NPC se dirige, pour l'intercepter au lieu d'arriver derrière lui. La création et l'édition des
circuits passent uniquement par les commandes.

## Limites connues

- **Le déplacement est un écrasement de vélocité, pas du pathfinding.** Les NPC ne
  contournent pas les obstacles. Un point de passage derrière un mur est un point de
  passage que le NPC n'atteindra jamais seul.
- **Les longs segments finissent téléportés.** Le chien de garde anti-blocage téléporte le
  NPC sur son point de passage après 60 secondes passées à plus de 2 blocs de celui-ci, ce
  qu'une marche légitime d'environ 70 blocs ou plus déclenche. Garde des segments courts.
- **L'arrêt « regarder un joueur » ne vérifie pas la ligne de vue.** Un NPC se tourne vers
  un joueur planté derrière un mur, et ne reprend pas sa route tant qu'il y reste.
- **Les NPC de patrouille ne peuvent pas être tués** tant que leur patrouille est
  enregistrée : `/kill` est bloqué par un mixin. Passe `/npcpatrol clear` d'abord.
- **Aucune route patrol ne peut exiger SUPER_ADMIN.** `POST /api/v1/patrol/tp` exige
  OPERATOR, appliqué à la fois par la route elle-même et par le répartiteur de modules de
  CheeseDash ; OPERATOR est aussi le plafond, parce que le répartiteur ne connaît pas de
  contrôle plus strict et qu'aucun des deux handlers n'appelle `requireAdmin`. Voir
  [dashboard](docs/dashboard.fr.md#post-tp).
- Jamais exécuté avec autre chose qu'EasyNPC. Le comportement avec un autre mod de NPC est
  une attente, pas une mesure.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le faire tourner sur un serveur qui reçoit des dons ou vend
des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
