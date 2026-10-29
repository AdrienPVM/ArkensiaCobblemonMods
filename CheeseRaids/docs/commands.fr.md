[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CheeseRaids expose une seule commande, `/cheeseraids`, et six sous-commandes. Cinq d'entre
elles exigent le **niveau de permission 2** — celui qu'un serveur vanilla accorde à un
opérateur. La sixième, `enter`, n'exige aucune permission et constitue la seule branche
destinée aux joueurs.

Chaque réponse est envoyée au seul appelant, en mauve clair, et **en français** — les
fichiers de langue du mod contiennent une unique clé (`cheeseraids.title`) et tous les
messages sont des `Text.literal` en dur. Il n'y a rien à traduire en changeant de pack de
ressources.

Les cinq sous-commandes d'administration prennent l'identifiant du raid comme premier
argument, en un seul mot. Il n'y a **aucune complétion par tabulation** pour lui :
l'argument est un simple `StringArgumentType.word()` sans fournisseur de suggestions, donc
il faut connaître l'identifiant. `/cheeseraids status` les liste.

---

## `/cheeseraids start`

```mcfunction
/cheeseraids start <raid>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `raid` | mot unique | oui | un identifiant de raid chargé, tel que l'affiche `status`. Pas de complétion | — |

Niveau de permission 2. Un joueur en dessous ne voit pas la sous-commande dans la complétion
par tabulation et reçoit l'erreur vanilla *Unknown or incomplete command*.

### Ce que fait la commande

1. Cherche la définition par identifiant. Refuse si elle n'existe pas ou si `enabled` vaut
   `false`.
2. Refuse si une instance de ce raid est déjà active. Une instance par identifiant de raid,
   toujours — en revanche, deux identifiants différents peuvent tourner en même temps.
3. Crée une `RaidInstance`, horodate `startedAt`, et passe en `ANNOUNCE`, ce qui diffuse le
   titre et la ligne de chat, masque le héraut et force un orage au-dessus de l'arène de
   vague.
4. Écrit `config/cheeseraids/instances/<raid>.json`, pour qu'un crash en pleine raid soit
   récupérable.
5. Diffuse un message `raid_update` sur le canal WebSocket `raids` du dashboard.

Ensuite le raid se déroule tout seul. Tout ce qui suit `ANNOUNCE` est piloté par le tick
serveur — voir [le modèle de raid](raid-model.fr.md#phases).

### Exemple

```mcfunction
/cheeseraids start hoopa
```

```text
Raid 'hoopa' démarré.
```

Dans la console, au même moment :

```text
[CheeseRaids] Wave for hoopa: spawned 5/5 mob(s) directly.
```

(cette ligne arrive `wave.graceSecondsBeforeWave` secondes plus tard, cinq par défaut).

### Échecs

| Message | Cause |
|---|---|
| `Impossible de démarrer '<id>' (inconnu/désactivé/déjà actif).` | une de trois choses : aucune définition sous cet identifiant, `enabled: false`, ou une instance tourne déjà |

Ce message unique couvre les trois cas, ce qui n'aide pas pendant un débogage.
`/cheeseraids status` lève l'ambiguïté : un identifiant absent de la liste est inconnu, un
identifiant affiché `[idle]` alors que la commande refuse toujours est désactivé, et tout le
reste est déjà actif.

Le résultat numérique de la commande vaut 1 en cas de succès et 0 en cas de refus : elle est
donc utilisable dans un `/execute store`.

---

## `/cheeseraids stop`

```mcfunction
/cheeseraids stop <raid>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `raid` | mot unique | oui | l'identifiant d'un raid **en cours** |

Niveau de permission 2.

Lance immédiatement le chemin de nettoyage, quelle que soit la phase. C'est-à-dire : météo
dégagée restaurée au-dessus de l'arène de vague, branche de parkour de chaque joueur
remplie d'air, volume de l'arène du boss vidé, mobs de vague et boss écartés, héraut masqué,
animations de révélation abandonnées, instance retirée de la mémoire et son fichier JSON
supprimé.

**Les joueurs ne sont pas téléportés.** `stop` n'appelle pas le chemin d'éjection — seules la
séquence de victoire et le timeout de combat le font. Un joueur présent dans la faille au
moment où tu arrêtes un raid se retrouve debout dans une dimension dont le sol vient d'être
supprimé. Téléporte-le d'abord, ou accepte qu'il tombe dans le vide d'une dimension dont
`enabled` vaut `false`.

```mcfunction
/cheeseraids stop hoopa
```

```text
Raid 'hoopa' stoppé.
```

```text
[CheeseRaids] Raid 'hoopa' ended and cleaned up.
```

| Message | Cause |
|---|---|
| `Aucune instance active pour '<id>'.` | rien ne tourne sous cet identifiant. Une définition qui existe mais est au repos donne ce message aussi |

---

## `/cheeseraids reload`

```mcfunction
/cheeseraids reload
```

Aucun argument. Niveau de permission 2.

Relit chaque `*.json` de `config/cheeseraids/raids/` et remplace la table de définitions en
mémoire. Les instances ne sont pas touchées : c'est un rechargement de configuration, pas un
redémarrage.

```text
Définitions de raids rechargées.
```

La réponse est inconditionnelle : elle dit la même chose que les fichiers aient été analysés
ou non. Un fichier mal formé est **laissé intact sur le disque** (il n'est jamais écrasé par
les valeurs par défaut) et la définition précédemment chargée reste en mémoire : une édition
cassée ressemble donc exactement à un succès, vue du jeu. La console est le seul endroit où
la vérité apparaît :

```text
[CheeseRaids] FAILED to parse raid def .../hoopa.json — fix the JSON syntax and
/cheeseraids reload. Left untouched. (Expected ':' at line 12 column 5)
```

Encore une particularité : le `hoopa.json` par défaut n'est écrit que si le dossier ne
contient **aucun** fichier `.json`. Si tous les fichiers qu'il contient sont mal formés, tu
te retrouves avec zéro définition chargée et aucun défaut écrit.

---

## `/cheeseraids status`

```mcfunction
/cheeseraids status
```

Aucun argument. Niveau de permission 2. C'est le seul inventaire des identifiants de raids
chargés que le mod propose en jeu.

```text
Raids:
• hoopa [ANNOUNCE, 0 j]  (auto 21h30)
• wailord_tide [idle]
```

| Champ | Signification |
|---|---|
| l'identifiant | le champ `id` de la définition, le mot que prennent toutes les autres sous-commandes |
| `[<PHASE>, N j]` | en vert, quand une instance tourne : la phase courante et le nombre de participants (`j` pour joueurs) |
| `[idle]` | en gris, quand aucune instance ne tourne. Ne dit rien de `enabled` |
| `(auto HHhMM)` | affiché seulement quand `schedule.autoTrigger` vaut `true` |

Les noms de phases sont ceux de l'énumération `RaidPhase`, à l'identique : `ANNOUNCE`,
`OVERWORLD_WAVE`, `NPC_REVEAL`, `OPEN`, `COMBAT`, `REWARD`, `CLEANUP`. `IDLE` n'apparaît
jamais ici, puisqu'une instance au repos n'est pas gardée en mémoire.

Le nombre de participants compte tous ceux qui sont entrés, y compris les joueurs
déconnectés depuis — l'état par joueur survit volontairement à une reconnexion.

---

## `/cheeseraids enter`

```mcfunction
/cheeseraids enter <raid>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `raid` | mot unique | oui | l'identifiant d'un raid actuellement en phase `OPEN` |

**Aucune permission requise.** C'est la seule sous-commande que tout joueur peut lancer, et
la seule qui doive être lancée par un joueur plutôt que par la console ou un bloc de
commande.

C'est aussi le chemin d'entrée de secours. Normalement les joueurs entrent en parlant au
héraut Ho-Oh ; quand celui-ci est introuvable, le mod diffuse cette commande dans le chat à
la place :

```text
La faille de Hoopa s'ouvre ! Tapez /cheeseraids enter hoopa pour entrer.
```

### Ce qu'elle fait, la première fois

1. Vérifie que le raid est actif et en `OPEN`.
2. S'assure que la dimension de faille existe, en la créant via CheeseGuard si besoin.
3. Réclame le plus petit slot libre, jusqu'à `arena.maxSlots`.
4. Dérive une graine propre au joueur depuis son UUID, l'identifiant du raid et le slot : la
   disposition de la branche est donc déterministe et identique d'une reconnexion à l'autre.
5. Génère la branche : la plateforme de départ et son portique apparaissent d'un coup, le
   reste du chemin est vidé puis animé, `arena.revealBlocksPerTick` blocs par tick après
   `arena.revealDelayTicks` ticks de montée en puissance.
6. Téléporte le joueur sur la plateforme de départ, face au chemin.

### Ce qu'elle fait au retour

Rien n'est régénéré. Le joueur est téléporté à son dernier checkpoint, ou à sa plateforme de
départ s'il n'en a jamais atteint un. C'est voulu et structurant : une reconnexion ne doit
jamais reconstruire une branche ni remettre la progression à zéro.

### Échecs

| Message | Cause |
|---|---|
| `Aucune invasion active.` | aucune instance sous cet identifiant, ou aucune définition |
| `La faille n'est pas ouverte.` | le raid tourne mais n'est pas en `OPEN` — trop tôt (la vague est encore debout) ou trop tard (le combat de boss a commencé) |
| `La faille n'a pas pu s'ouvrir.` | la dimension de faille n'a pu être ni créée ni résolue. Cherche `Failed to create rift dimension` dans la console |
| `La faille est pleine.` | les `arena.maxSlots` branches sont toutes prises |
| `Réservé aux joueurs.` | lancée depuis la console ou un bloc de commande |

Un échec renvoie 0 et ne change rien : aucun slot n'est consommé et aucune branche n'est
générée.

---

## `/cheeseraids herald`

```mcfunction
/cheeseraids herald <raid> info
/cheeseraids herald <raid> show
/cheeseraids herald <raid> hide
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `raid` | mot unique | oui | un identifiant de raid chargé |
| l'action | littéral | oui | `info`, `show` ou `hide` |

Niveau de permission 2. Attention à l'ordre : **l'identifiant du raid vient avant
l'action.** L'ancien README du mod donnait l'inverse.

Ce sont des outils de calibration, pas des rouages du raid. Ils fonctionnent qu'un raid soit
actif ou non.

### `info`

Affiche où le héraut est attendu, et chaque EasyNPC que le serveur voit à proximité. C'est la
commande la plus utile du mod pendant la mise en place.

```mcfunction
/cheeseraids herald hoopa info
```

```text
Héraut attendu: 118, 71, -2050 dans minecraft:overworld
EasyNPC dans 10 blocs: 1
  • easy_npc:humanoid_slim @ 118, 71, -2050 (dist 0.5, invisible=true)
```

Trois autres choses qu'elle peut t'apprendre. `Monde '<id>' non chargé.` signifie que
`npc.pos.world` ne désigne aucun monde chargé. `Autres EasyNPC dans 128 blocs (mauvaise
position ?)` liste les NPC qui existent mais se trouvent hors de la boîte de 10 blocs — une
faute de frappe dans les coordonnées. `⚠ AUCUN EasyNPC chargé à proximité` signifie que rien
n'a été trouvé dans les 128 blocs non plus, ce qui traduit d'ordinaire un chunk non chargé
plutôt qu'un NPC manquant.

### `show` et `hide`

Basculent l'attribut d'affichage `VISIBLE` de chaque EasyNPC situé dans les 10 blocs autour
de `npc.pos`, et le contour lumineux vanilla avec lui.

```mcfunction
/cheeseraids herald hoopa show
```

```text
1 EasyNPC rendu(s) visible(s).
```

```mcfunction
/cheeseraids herald hoopa hide
```

```text
1 EasyNPC masqué(s).
```

| Message | Cause |
|---|---|
| `Raid inconnu.` | aucune définition sous cet identifiant |
| `0 EasyNPC rendu(s) visible(s).` | rien trouvé dans la boîte. Lance `info` |

Une subtilité dans le décompte : il indique combien d'EasyNPC ont été **trouvés**, pas
combien ont été basculés avec succès. Si l'API d'affichage d'EasyNPC a changé, tu peux
obtenir `1 EasyNPC rendu(s) visible(s).` sans aucun changement visible en jeu. La console
sépare les deux :

```text
[CheeseRaids] herald setVisible(true) — 1 EasyNPC(s) near 118, 71, -2050, 1 toggled via display-attr
```

`1 toggled` est le nombre qui compte. `0 toggled` avec `1 EasyNPC(s) near` signifie que la
réflexion a échoué ; voir [les intégrations](integrations.fr.md#easynpc).

Le résultat numérique vaut 1 quand au moins un NPC a été trouvé, 0 sinon, et le `-1` interne
devient le message `Raid inconnu.`

---

## `/cheeseraids testreward`

```mcfunction
/cheeseraids testreward <raid>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `raid` | mot unique | oui | un identifiant de raid chargé |

Niveau de permission 2. Doit être lancée par un joueur : l'appelant devient le propriétaire
auquel l'apparition du portail est attribuée.

Ouvre `max(1, reward.minPortals)` portails CobbleSafari autour de `reward.portalAnchor` et
tire trois feux d'artifice à chacun. Elle n'accorde **pas** `reward.fromage`, n'exécute pas
`reward.commands`, et ne touche à aucun état de raid. C'est une vérification de position et
de dépendance, pas une répétition de la récompense — le nom promet plus qu'il ne tient.

```mcfunction
/cheeseraids testreward hoopa
```

```text
Test récompense: 1/1 portail(s) Hoopa à 100, 71, -2030 (minecraft:overworld)
```

| Message | Cause |
|---|---|
| `Monde d'ancrage introuvable: <id>` | `reward.portalAnchor.world` ne désigne aucun monde chargé |
| `Test récompense: 0/N …` | CobbleSafari est absent, ou ses méthodes de portail n'ont pu être résolues, ou il a refusé le placement |
| rien du tout, résultat 0 | `raid` n'est pas un identifiant connu, ou la commande a été lancée depuis la console |

La dernière ligne est un trou : un identifiant inconnu ne produit strictement aucun retour.
Vérifie l'identifiant avec `status` d'abord.

---

## Piloter le mod depuis ailleurs

Trois de ces sous-commandes ont un équivalent sur le dashboard, et le planificateur en
couvre une quatrième :

- **Dashboard** — le module `raids` expose démarrer, arrêter et recharger. Voir
  [l'API dashboard](dashboard.fr.md).
- **Planificateur** — `schedule.autoTrigger` démarre un raid à une heure donnée sans que
  personne tape quoi que ce soit. Voir [la configuration](configuration.fr.md#schedule).

`enter`, `herald` et `testreward` n'ont aucun équivalent distant.

---

← [Prise en main](getting-started.fr.md) · [Configuration](configuration.fr.md) →
