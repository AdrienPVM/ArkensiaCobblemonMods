[← CheeseVanity](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](visibility.md)

# Qui voit quoi

C'est la première question que pose n'importe qui à propos d'un mod de visibilité
cosmétique, donc elle a sa propre page. Réponse courte : masquer un slot le masque **pour
tout le monde qui a le mod installé côté client**, toi compris, en vue première personne
comme en troisième. Ce n'est pas une illusion privée côté client.

La réponse longue suit, parce que la frontière exacte compte le jour où la moitié de tes
joueurs lance le modpack et l'autre moitié se connecte avec un client Fabric nu.

---

## Le modèle de données

Tout le mod tient dans un seul état : un ensemble de **slot keys** par UUID de joueur.

```text
3f2a... (UUID du joueur)  ->  { "chest/back", "head/hat", "boots" }
```

Une slot key est une courte chaîne. Il en existe deux sortes, toutes deux produites par
`HandledScreenMixin.resolveSlotKey` :

| Sorte | Format de la clé | Exemples | Produite depuis |
|---|---|---|---|
| Slot Trinkets | `<groupe>/<nom>` | `chest/back`, `head/hat`, `hand/ring` | le `SlotType` du slot, lu par réflexion |
| Slot d'armure vanilla | un mot fixe | `helmet`, `chestplate`, `leggings`, `boots` | l'index du slot dans `PlayerInventory` (39, 38, 37, 36) |

Une slot key présente dans l'ensemble d'un joueur signifie « ne rends pas ce qui se trouve
dans ce slot ». Absente, elle signifie « rends-le normalement ». Il n'y a pas de troisième
état, pas d'état par spectateur et pas d'état par monde.

Les groupes et les noms côté Trinkets viennent des mods installés, puisque les slots
Trinkets sont déclarés par datapack. `chest/back` est le slot que les mods de sac à dos
utilisent couramment, `head/hat` celui des mods de chapeaux cosmétiques. Relève les clés
sur ton propre serveur plutôt que de faire confiance à une liste : la ligne de log décrite
dans [commands.fr.md](commands.fr.md#lire-la-slot-key-dun-slot) affiche la clé résolue à
chaque appui sur la touche de bascule.

---

## L'aller-retour, étape par étape

1. Tu appuies sur `=` avec le curseur sur un slot éligible, dans n'importe quel écran de
   conteneur. `HandledScreenMixin.onKeyPressed` résout le slot en une clé et envoie un
   payload `cheesevanity:toggle_slot` contenant cette clé. Rien n'est décidé côté client.
2. Le serveur le reçoit dans `CheeseVanityMod.onInitialize`. Il rejette la clé si elle est
   nulle, vide, plus longue que 64 caractères, ou non basculable selon la configuration
   courante (voir [configuration.fr.md](configuration.fr.md)). Une clé rejetée ne produit
   aucun retour pour le joueur. Seul le refus de la configuration laisse une ligne dans le
   log serveur (`Slot '…' not toggleable …`) ; une clé nulle, vide ou trop longue est
   écartée sans aucun log.
3. Sur le thread serveur, `VanityData.toggle` inverse l'appartenance de cette clé à ton
   ensemble, et `VanityData.save` réécrit immédiatement
   `config/cheesevanity_data.json`.
4. Tu reçois un message en barre d'action : `[CheeseVanity] chest/back hidden` en rouge,
   ou `[CheeseVanity] chest/back visible` en vert.
5. Le serveur diffuse `cheesevanity:sync_hidden` — ton UUID et l'ensemble complet de tes
   clés masquées — à **tous les joueurs connectés**, toi compris.
6. Chaque client qui reçoit le paquet stocke l'ensemble dans `VanityClientState`. Dès
   l'image suivante, les deux mixins de rendu consultent ce cache et sautent les pièces
   masquées.

Comme l'étape 5 va à tout le monde et que l'étape 6 se produit sur chaque client qui a le
mod, le résultat est cohérent : toi et tous les autres joueurs moddés voyez le même
modèle.

---

## La frontière exacte

**Toi, en première personne.** Le jeu ne dessine ni armure ni trinket sur les bras en vue
première personne : il n'y a rien à masquer et rien ne change.

**Toi, en troisième personne (F5) et dans les aperçus de modèle d'autres mods.** Ton
modèle passe par les mêmes `ArmorFeatureRenderer` et `TrinketFeatureRenderer` que celui de
n'importe qui, indexés sur ton propre UUID. Les pièces masquées disparaissent.

**Les autres joueurs, avec CheeseVanity installé.** Ils ont reçu le paquet de
synchronisation et leur copie de `VanityClientState` contient ton ensemble. Ils voient ton
modèle exactement comme toi.

**Les autres joueurs, sans CheeseVanity installé.** ⚠️ Ils voient tout. Le masquage est
assuré entièrement par deux mixins de rendu côté client ; un client qui n'a pas le mod n'a
ni mixin ni cache, et rend ton armure et tes trinkets normalement. Le serveur ne retire
jamais rien et ne rééquipe jamais rien, donc il n'existe aucun moyen côté serveur de faire
plier un client non moddé. Si la cohérence visuelle compte sur ton serveur, CheeseVanity
doit être un mod client obligatoire, pas optionnel.

**Les mobs, les porte-armures, les autres entités.** `ArmorFeatureRendererMixin` s'indexe
sur `entity.getUuid()` et le cache ne contient jamais que des UUID de joueurs : un
porte-armure qui porte le même casque n'est pas affecté.

**Ton propre écran d'inventaire.** Les slots masqués continuent d'afficher l'item, avec un
remplissage rouge translucide et une croix rouge dessinés par-dessus par
`HandledScreenMixin.afterDrawSlot`. Cette surcouche n'est dessinée que pour les slots qui
t'appartiennent — elle lit `client.player.getUuid()` — donc elle marque ta préférence, pas
celle de quelqu'un d'autre.

---

## Ce que le serveur sait et ce qu'il ignore

Le serveur est le propriétaire de la préférence. Il la stocke, la valide, la persiste et la
diffuse. Ce qu'il ne fait **pas** :

- il ne change pas ton équipement. Un plastron masqué donne toujours ses points d'armure,
  un trinket masqué accorde toujours son effet. Rien n'est déséquipé, déplacé ni désactivé.
- il ne rend rien, donc un serveur qui fait tourner CheeseVanity sans aucun client moddé
  ne produit absolument aucun effet visible.
- il ne dit jamais à un client d'oublier un joueur. Il n'existe pas de paquet
  « effacer » ; `VanityClientState.clear()` existe dans le code source mais n'est jamais
  appelé. Voir le symptôme du cache périmé dans
  [troubleshooting.fr.md](troubleshooting.fr.md) pour ce que cela coûte.

---

## Persistance

Ta préférence vit dans `config/cheesevanity_data.json`, réécrit à chaque bascule :

```json
{
  "players": {
    "3f2a6b40-0000-4000-8000-000000000001": [
      "chest/back",
      "head/hat"
    ]
  }
}
```

Les joueurs dont l'ensemble est vide sont retirés du fichier au lieu d'être écrits comme
`[]`.

**Elle survit à une déconnexion et à un redémarrage du serveur.** Le fichier est lu une
seule fois, dans `VanityData.load()`, pendant l'initialisation du mod, et toute la table
reste en mémoire ensuite. Déconnecte-toi, reviens deux semaines plus tard : ta cape est
toujours masquée.

**Elle ne te suit pas sur un autre serveur.** Le fichier appartient à une installation de
serveur, indexé par UUID de joueur. Un réseau avec un proxy et plusieurs serveurs
d'arrière-plan obtient des préférences distinctes, sauf si ces serveurs partagent un même
dossier `config/`.

**Elle n'est pas liée à l'item.** L'ensemble stocke des slot keys, jamais des identités
d'items. Masque `chest/back`, puis remplace le sac à dos de ce slot par un autre : le
nouveau est masqué aussi. La préférence porte sur le slot, pas sur son contenu.

---

## Interaction avec la dépendance optionnelle Trinkets

Quand Trinkets est absent, `resolveSlotKey` attrape `ClassNotFoundException` et tombe
directement dans la branche des armures vanilla, et
`cheesevanity-trinkets.mixins.json` — déclaré `"required": false` — ne s'applique tout
pas du tout. Rien ne casse. Ce que tu perds, c'est toute clé `<groupe>/<nom>`,
c'est-à-dire l'intégralité des fonctionnalités par défaut du mod, puisque `enableTrinkets`
vaut `true` et `enableVanillaArmor` vaut `false` à l'installation. Sans Trinkets et sans
modifier la configuration, appuyer sur `=` ne fait rien, nulle part.

L'implémentation du masquage des trinkets mérite d'être connue si tu maintiens ce mod.
Plutôt que de filtrer le rendu, `TrinketFeatureRendererMixin` vide les `TrinketInventory`
masqués juste avant l'exécution de `TrinketFeatureRenderer.render` et y remet les piles au
`TAIL`. Il mute des objets d'inventaire vivants le temps d'un appel de rendu. Les
faiblesses qui découlent de ce choix sont listées dans
[architecture.fr.md](architecture.fr.md#faiblesses-connues).

---

← [Configuration](configuration.fr.md) · [Architecture](architecture.fr.md) →
