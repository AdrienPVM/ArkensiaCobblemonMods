[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

Tout ce que CheeseKart conserve tient dans un seul fichier :

```text
config/cheesekart.json
```

Il n'y a aucun réglage global — pas d'interrupteur maître, pas de limites, pas de temps
de recharge. Le fichier n'est rien d'autre qu'une liste de presets, et un preset est un
paquet de valeurs de physique accompagné de deux drapeaux de comportement. Lancer un
kart consiste à choisir l'un de ces paquets et à le pousser vers le client d'un joueur.

Le fichier est créé au premier démarrage s'il est absent ou s'il ne contient aucun
preset, semé avec les trois presets fournis décrits [plus bas](#les-trois-presets-fournis).
Il est réécrit chaque fois qu'un preset est créé, modifié ou supprimé, que ce soit depuis
le dashboard ou depuis une commande, et il est écrit indenté pour rester lisible après
que le mod y a touché.

> ⚠️ Le fichier est lu **une seule fois**, au démarrage du serveur. L'éditer à la main
> pendant que le serveur tourne n'a aucun effet, et le prochain changement de preset fait
> depuis le dashboard écrasera tes modifications avec le contenu de la mémoire. Édite les
> presets depuis le dashboard, ou arrête le serveur d'abord.

---

## Forme du fichier

```json
{
  "presets": [
    { "id": "rally", "name": "Rally", "...": "..." },
    { "id": "drift", "name": "Drift", "...": "..." }
  ]
}
```

Un objet racine avec un unique tableau `presets`. Un preset dont l'`id` est absent ou
`null` est écarté en silence au chargement, et toute clé inconnue est ignorée — ajouter
ton propre champ de notes à un preset est donc sans danger, mais il sera effacé à la
prochaine sauvegarde du mod.

---

## Champs d'un preset

Seize champs. Douze sont des valeurs de physique transmises à OpenBoatUtils, deux
relèvent de l'identité, deux changent le comportement de la session.

> Les **types, valeurs par défaut et valeurs des presets fournis** ci-dessous sortent
> directement de `KartPreset.java` et sont exacts. Les **descriptions de la sensation que
> donne chaque valeur** sont une autre affaire : CheeseKart ne fait que transmettre ces
> nombres, et c'est OpenBoatUtils qui les interprète. Ces remarques viennent de la
> conduite des presets, pas de la lecture du code d'OBU : traite-les comme un guide de
> réglage, pas comme une spécification. Si OBU change sa façon de lire un réglage, le
> nombre reste juste et la description peut cesser de l'être.

### Identité

| Clé | Type | Défaut | Rôle |
|---|---|---|---|
| `id` | chaîne | — | La poignée utilisée par les commandes, l'API et le fichier de sauvegarde. Obligatoire. Le dashboard impose `[a-z0-9_-]+` ; un fichier écrit à la main n'est pas vérifié, donc tiens-t'en à cette forme toi-même |
| `name` | chaîne | — | Libellé d'affichage, montré dans `/cheesekart presets` et dans le dashboard. Texte libre |

### Adhérence et motricité

| Clé | Type | Défaut | Rôle |
|---|---|---|---|
| `slipperiness` | float | `0.98` | La part de vitesse que le kart ne perd pas à chaque tick. En dessous de 1 le kart accroche ; à 1 il conserve sa vitesse ; au-dessus de 1 il accélère tout seul et glisse dans les virages |
| `forwardAccel` | float | `0.04` | L'accélération quand on maintient la touche avant. Le plus gros levier sur la vitesse ressentie d'une piste |
| `backwardAccel` | float | `0.005` | L'accélération en marche arrière. Maintenue un ordre de grandeur sous l'avant dans tous les presets fournis, pour que la marche arrière soit une manœuvre de récupération et non un style de conduite |
| `accelStacking` | booléen | `false` | À `true`, l'accélération s'ajoute à la vitesse actuelle au lieu d'être plafonnée par elle. Supprime toute vitesse de pointe. Laisse-le désactivé, sauf si c'est l'effet que tu cherches |

`slipperiness` est la valeur à toucher en premier. `0.98` est du bitume ferme ; `1.1` —
le preset `drift` — est assez glissant pour que le kart emporte son élan dans un virage
au lieu de le dissiper. Passé environ `1.15`, un kart cesse d'être conduisible en ligne
droite, parce qu'il gagne plus de vitesse que le joueur ne peut en contrer au volant.

### Direction

| Clé | Type | Défaut | Rôle |
|---|---|---|---|
| `yawAccel` | float | `1.0` | La vitesse de rotation du kart quand on tourne. `2.0`, comme dans `drift`, braque presque deux fois plus sec |
| `turnForwardAccel` | float | `0.005` | Accélération avant supplémentaire appliquée pendant un virage. De petites valeurs positives évitent qu'un kart ne meure dans une épingle |

Un `yawAccel` élevé combiné à une `slipperiness` élevée, c'est ce qui fait une piste de
drift : le kart pivote vite mais continue d'aller là où il allait déjà.

### Comportement vertical

| Clé | Type | Défaut | Rôle |
|---|---|---|---|
| `stepHeight` | float | `1.25` | La hauteur de bloc que le kart franchit sans sauter. `1.25` passe un bloc plus une dalle ; `2.0` — le preset `parkour` — monte des marches de deux blocs |
| `gravity` | double | `-0.04` | L'accélération vers le bas. **Le négatif descend.** Les bateaux vanilla sont proches de `-0.04` ; `-0.03` donne des sauts plus longs et plus flottants |
| `jumpForce` | float | `0.0` | L'impulsion vers le haut au saut. À `0.0` le kart ne peut pas sauter du tout, ce qui est le bon réglage pour une course au sol |
| `airControl` | booléen | `true` | Si la direction répond encore en l'air. Désactivé, un saut engage le pilote sur sa trajectoire |
| `airStepping` | booléen | `true` | Si `stepHeight` s'applique aussi en vol, ce qui permet à un kart d'accrocher une corniche au passage |
| `fallDamage` | booléen | `false` | Si un atterrissage brutal fait mal. Désactivé dans tous les presets fournis — une course qui se termine sur un écran de mort n'est pas une course |

Mettre une `gravity` positive fait monter le kart en continu. Ce n'est pas utile, mais
rien ne t'en empêche.

### Comportement de la session

| Clé | Type | Défaut | Rôle |
|---|---|---|---|
| `locked` | booléen | `true` | Si le pilote est retenu dans le kart. Voir [le mode verrouillé](physics.fr.md#le-mode-verrouillé) |
| `species` | chaîne | `""` | Une espèce Cobblemon en laquelle le pilote est transformé pour la durée de la session, via CobbleShift. Vide signifie aucune transformation. L'argument `species` de `/cheesekart start` prend le dessus |

`locked` mérite un instant d'attention. Activé — c'est le défaut — appuyer sur Shift ne
fait rien : la descente côté client est annulée, et si le joueur sort malgré tout, le
serveur le remet en selle au tick suivant. Désactivé, descendre met proprement fin à la
session. Utilise `locked: true` pour une course chronométrée dont personne ne doit
pouvoir sortir à pied, et `false` pour un kart de balade que l'on peut garer.

---

## Les trois presets fournis

Écrits au premier démarrage, et un point de départ raisonnable pour les tiens. Tout champ
non listé ici reste à sa valeur par défaut du tableau ci-dessus.

### `rally` — adhérence ferme, prévisible

```json
{
  "id": "rally",
  "name": "Rally",
  "slipperiness": 0.98,
  "stepHeight": 1.25,
  "gravity": -0.04,
  "forwardAccel": 0.04,
  "backwardAccel": 0.005,
  "yawAccel": 1.0,
  "airControl": true,
  "fallDamage": false
}
```

La référence. Il accroche, braque à un rythme sain, franchit un bloc et demi.

### `drift` — lâche et rapide

```json
{
  "id": "drift",
  "name": "Drift",
  "slipperiness": 1.1,
  "stepHeight": 1.25,
  "gravity": -0.04,
  "forwardAccel": 0.06,
  "backwardAccel": 0.005,
  "yawAccel": 2.0,
  "airControl": true,
  "fallDamage": false
}
```

Une fois et demie l'accélération, le double de la vitesse de braquage, et une glissance
au-dessus de 1 pour que la vitesse survive à un virage. Récompense le pilote qui connaît
la piste ; punit celui qui ne la connaît pas.

### `parkour` — il grimpe et il saute

```json
{
  "id": "parkour",
  "name": "Parkour",
  "slipperiness": 0.98,
  "stepHeight": 2.0,
  "gravity": -0.03,
  "forwardAccel": 0.04,
  "backwardAccel": 0.005,
  "yawAccel": 1.0,
  "jumpForce": 0.6,
  "airControl": true,
  "airStepping": true,
  "fallDamage": false
}
```

Le seul preset fourni qui puisse sauter. Marches de deux blocs, gravité allégée et pas
aérien font d'un kart quelque chose qui peut affronter un parcours vertical.

---

## Un fichier complet annoté

Copie-le comme point de départ. Les commentaires **ne sont pas** du JSON valide —
retire-les avant usage ; ils sont là pour être lus, pas analysés.

```json
{
  "presets": [
    {
      "id": "ice-sprint",            // identifiant utilisé par /cheesekart start
      "name": "Ice Sprint",          // affiché dans les listes

      "slipperiness": 1.12,          // très glissant : garde la vitesse en courbe
      "forwardAccel": 0.07,          // rapide
      "backwardAccel": 0.005,        // la marche arrière reste un rattrapage
      "accelStacking": false,        // conserver une vitesse maximale

      "yawAccel": 2.2,               // braquage vif pour contrer la glisse
      "turnForwardAccel": 0.008,     // ne pas mourir dans les épingles

      "stepHeight": 1.25,            // un bloc plus une dalle
      "gravity": -0.04,              // standard
      "jumpForce": 0.0,              // course au sol, pas de saut
      "airControl": true,
      "airStepping": false,          // un saut t'engage
      "fallDamage": false,           // ne jamais tuer un pilote

      "locked": true,                // maintenu dans le kart jusqu'à l'arrêt par un admin
      "species": ""                  // aucune transformation CobbleShift
    }
  ]
}
```

Avec le serveur en marche, le même preset se crée depuis le dashboard ou avec une seule
requête — et cette voie-là valide l'identifiant, refuse un doublon, et applique les
changements aux sessions en cours. Voir [l'API dashboard](dashboard.fr.md#post-presets).

---

← [Commandes](commands.fr.md) · [Physique et sessions](physics.fr.md) →
