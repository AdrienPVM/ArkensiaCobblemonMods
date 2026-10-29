[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CobbleShift expose une seule commande, `/cshift`, et treize sous-commandes. Tout l'arbre
est verrouillé par `requires(src -> src.hasPermissionLevel(2))` sur le littéral racine :
**chaque** sous-commande exige donc le niveau de permission 2, celui qu'un serveur
vanilla accorde à un opérateur. Un joueur sous ce niveau ne voit pas `/cshift` dans la
complétion par tabulation et reçoit l'erreur vanilla *Unknown or incomplete command*
s'il la tape quand même.

Aucune commande n'est destinée aux joueurs. Ils sont transformés par un administrateur,
par un effet d'événement CheeseGuard, ou par CheeseKart quand ils sont mis en kart. La
seule entrée côté joueur, ce sont les deux raccourcis clavier client (pose de sommeil,
cri), décrits dans
[le modèle de transformation](transforms.fr.md#ce-que-le-joueur-peut-faire-une-fois-transformé).

Les sous-commandes se répartissent en quatre familles :

| Famille | Sous-commandes |
|---|---|
| Transformations de joueur | `transform`, `revert`, `revertall` |
| Capacités passives | `ability <player> [pokemon]`, `ability global enable\|disable`, `ability toggle <player>` |
| Transformations de PNJ | `transformnpc`, `revertnpc`, `revertallnpc` |
| Réglage et inspection | `scale`, `speed`, `scalenpc`, `speednpc`, `list`, `species` |

---

## `/cshift transform`

```mcfunction
/cshift transform <player> <pokemon [shiny] [noattacks] [form…]>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | sélecteur de joueur | oui | un joueur **connecté** ; `EntityArgumentType.player()`, donc les sélecteurs comme `@p` fonctionnent | — |
| `pokemon` | chaîne gourmande | oui | un nom d'espèce, puis un nombre quelconque de drapeaux ou d'aspects | — |

Le second argument est gourmand : il avale la fin de la ligne et le mod l'analyse
lui-même. La grammaire n'est positionnelle que sur un point : **l'espèce doit venir en
premier** ; les mots qui suivent peuvent apparaître dans n'importe quel ordre.

| Mot | Effet |
|---|---|
| `shiny` | affiche la texture chromatique |
| `noattacks` | le joueur ne peut pas lancer de projectiles de type tant qu'il est transformé |
| tout autre mot | traité comme un aspect Cobblemon, `_` réécrit en `-` |

Le traitement des aspects mérite d'être lu avant de s'en servir. Un mot ordinaire comme
`alolan` devient l'aspect `alolan`. Un mot contenant `=` est une paire
caractéristique-valeur : `magikarp_jump=black-mask` devient `magikarp-jump-black-mask`.
Plusieurs mots sont joints par une **espace**, parce que `PokemonProperties.parse()` de
Cobblemon sépare les aspects par des espaces et réserve le tiret à l'intérieur d'un nom
d'aspect unique. `mega midnight` fait donc deux aspects, tandis que `mega-x` n'en fait
qu'un.

La complétion par tabulation tient compte du contexte : sur le premier mot, elle propose
chaque nom d'espèce que le mod a résolu depuis Cobblemon ; ensuite, elle propose
`shiny`, `noattacks` et les six noms de forme codés en dur dans `KNOWN_FORMS` : `alolan`,
`galarian`, `hisuian`, `paldean`, `mega`, `gmax`. Cette liste est une commodité, pas une
liste de validation : tu peux taper n'importe quelle chaîne d'aspect acceptée par
Cobblemon, même si elle n'est pas proposée.

Un préfixe d'espace de noms `cobblemon:` sur l'espèce est retiré avant la recherche :
`cobblemon:pikachu` et `pikachu` sont la même requête.

### Ce que fait la commande

1. Vérifie le cooldown de transformation, mais seulement si le joueur n'est **pas** déjà
   transformé. Changer d'espèce en étant transformé contourne le cooldown, par
   conception.
2. Résout l'espèce via `SpeciesMapper`. Si Cobblemon est absent ou si le nom est
   inconnu, la commande échoue ici et rien n'est appliqué.
3. Annule d'abord toute transformation existante, sans le son d'annulation.
4. Applique une Invisibilité permanente, puis les modificateurs d'attribut de vitesse,
   de santé maximale et d'échelle dérivés des statistiques de base de l'espèce.
5. Diffuse le changement de modèle à tous les clients connectés, le cri et les
   particules n'étant perceptibles qu'à moins de 32 blocs, dans la même dimension.
6. Envoie au joueur `✦ Vous êtes transformé en <name> !`.

Le détail de chaque modificateur appliqué se trouve dans
[le modèle de transformation](transforms.fr.md).

### Exemples

```mcfunction
/cshift transform Steve charizard
```

```text
✦ Steve transformé en charizard
```

Steve voit aussi `✦ Vous êtes transformé en charizard !`.

```mcfunction
/cshift transform Steve lycanroc shiny midnight noattacks
```

```text
✦ Steve transformé en lycanroc shiny midnight [no attacks]
```

La ligne de retour reprend le résultat de l'analyse : c'est un moyen peu coûteux de
vérifier que l'ordre de tes mots a produit les aspects voulus.

### Échecs

| Message | Cause |
|---|---|
| `Échec : espèce '<name>' introuvable ou Cobblemon non disponible` | `SpeciesMapper.resolve()` n'a rien renvoyé. Soit le nom d'espèce est faux, soit Cobblemon n'est pas installé, auquel cas **toutes** les transformations échouent de cette façon |
| `⛔ Cooldown : encore <n>s avant de pouvoir se transformer.` | `transformCooldownEnabled` est actif et le joueur a annulé sa transformation il y a moins de `transformCooldownSeconds`. Envoyé au **joueur ciblé** dans la barre d'action. Celui qui lance la commande reçoit à la place la ligne générique `Échec : espèce … introuvable` ci-dessus, car `transform()` renvoie seulement `false` |
| *No player was found* (vanilla) | l'argument `player` ne correspond à aucun joueur connecté |

C'est le cas du cooldown qui déroute les administrateurs : du côté de celui qui lance la
commande, il ressemble à un échec de recherche d'espèce, même pour une espèce qui existe.

---

## `/cshift revert`

```mcfunction
/cshift revert <player>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `player` | sélecteur de joueur | oui | un joueur connecté |

Retire l'Invisibilité, enlève les trois modificateurs d'attribut, ramène la santé au
maximum réel si le joueur le dépasse désormais, diffuse une synchronisation à espèce vide
pour que les clients abandonnent le modèle, fait apparaître les particules d'annulation
et joue `ENTITY_VILLAGER_CELEBRATE` à la position du joueur. Elle enregistre aussi
l'heure de l'annulation, qui sert de point de départ au cooldown de transformation.

```mcfunction
/cshift revert Steve
```

```text
✦ Steve — transformation annulée
```

| Message | Cause |
|---|---|
| `<name> n'est pas transformé` | aucune transformation active pour ce joueur. Rien ne s'est passé |

---

## `/cshift revertall`

```mcfunction
/cshift revertall
```

Sans argument. Annule toutes les transformations actives. Un joueur dont l'entrée existe
mais qui est déconnecté est retiré de la table au lieu d'être rétabli, puisqu'il n'y a
personne à qui appliquer le nettoyage ; les modificateurs restés sur ce joueur sont
balayés à sa prochaine connexion par `cleanupStaleTransform`.

```text
✦ 4 transformation(s) annulée(s)
```

Le résultat numérique de la commande est le nombre de transformations actives **avant**
le balayage, ce qui permet de le capturer avec `/execute store`.

Le même `revertAll()` s'exécute à `SERVER_STOPPING` : c'est pour cela qu'un arrêt propre
ne laisse jamais de joueur transformé derrière lui. Un crash, si ; voir
[le modèle de transformation](transforms.fr.md#déconnexions-redémarrages-et-crashs).

---

## `/cshift ability <player> [pokemon]`

```mcfunction
/cshift ability <player>
/cshift ability <player> <pokemon>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | sélecteur de joueur | oui | un joueur connecté | — |
| `pokemon` | mot unique | non | un nom d'espèce ; se complète depuis toutes les espèces résolues | — |

Avec une espèce, la capacité passive du joueur est **forcée** sur le type primaire de
cette espèce et cesse de suivre son équipe Cobblemon. Sans espèce, le forçage est levé et
le joueur revient en mode automatique.

```mcfunction
/cshift ability Steve gengar
```

```text
✦ Ability forcée pour Steve → gengar
```

```mcfunction
/cshift ability Steve
```

```text
✦ Ability forcée retirée pour Steve — retour mode auto
```

Aucune des deux formes ne valide le nom d'espèce : `forceAbility` le résout sur-le-champ
et, si l'espèce est inconnue, enregistre l'entrée forcée avec le type `normal` au lieu
d'échouer. Une faute de frappe produit donc une entrée forcée sur `NORMAL`, lequel
n'accorde aucun effet.
Vérifie avec `/cshift list`, où les entrées forcées sont marquées `[FORCED]`.

⚠️ Cette sous-commande partage sa position avec les littéraux `global` et `toggle`.
Brigadier essaie les littéraux avant les arguments : un joueur qui s'appellerait
réellement `global` ou `toggle` ne peut donc pas être ciblé par cette forme.

---

## `/cshift ability global enable|disable`

```mcfunction
/cshift ability global enable
/cshift ability global disable
```

Aucun argument au-delà du littéral. Écrit `abilityGlobalEnabled` dans
`config/cobbleshift.json` immédiatement : c'est la seule sous-commande qui écrit le fichier
de configuration (les sous-commandes de PNJ écrivent le fichier de sauvegarde des PNJ). Les capacités passives sont **désactivées** par défaut, c'est donc cet
interrupteur qui active tout le sous-système.

La désactivation appelle aussi `deactivateAllAndSync()`, qui vide la table des capacités
actives et pousse une synchronisation de HUD vide à chaque joueur, pour qu'aucun client ne
reste à afficher une capacité qu'il n'a plus. Elle ne retire pas les effets déjà accordés :
ils ne sont plus rafraîchis et s'éteignent d'eux-mêmes en `abilityEffectDuration` ticks.

```text
✦ Abilities globales activées
```

---

## `/cshift ability toggle <player>`

```mcfunction
/cshift ability toggle <player>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `player` | sélecteur de joueur | oui | un joueur connecté |

Inverse l'exclusion d'un joueur. Le basculement est un test d'appartenance à un ensemble
d'UUID en mémoire : il n'est **pas** persisté et revient à zéro au redémarrage.

```text
✦ Abilities désactivées pour Steve
```

Le mot d'état du message reflète le nouvel état : `désactivées` quand le joueur vient
d'être exclu, `activées` quand il vient d'être réintégré.

---

## `/cshift list`

```mcfunction
/cshift list
```

Les transformations actives et les capacités actives, envoyées uniquement à celui qui
lance la commande.

```text
═══ CobbleShift Status ═══
Transforms actives : 2
  • Steve → charizard (BST=534, 7min)
  • Alex → pikachu (BST=320, 0min)
Abilities actives : 1
  • gengar (ghost) [FORCED]
Abilities globales : ON
```

| Champ | Signification |
|---|---|
| `BST=` | le total des statistiques de base de l'espèce, qui détermine le nombre de projectiles |
| `…min` | les minutes entières écoulées depuis le début de la transformation |
| `(type)` | le type primaire sur lequel la capacité s'est résolue |
| `[FORCED]` | posé par `/cshift ability <player> <pokemon>` au lieu d'être lu dans l'équipe |
| `Abilities globales :` | la valeur actuelle de `abilityGlobalEnabled` |

Les lignes de capacités affichent l'espèce et le type, mais pas le nom du joueur :
l'enregistrement porte l'UUID et la commande ne le résout pas. C'est un manque, pas un
choix de conception.

---

## `/cshift species <name>`

```mcfunction
/cshift species <name>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `name` | mot unique | oui | un nom d'espèce ; se complète depuis toutes les espèces résolues |

Affiche les statistiques brutes de Cobblemon et toutes les valeurs que CobbleShift en
dérive. Utilise-la avant une transformation pour voir ce qu'une espèce fera réellement
ressentir.

```mcfunction
/cshift species charizard
```

```text
═══ charizard ═══
Types: fire / flying
Stats: HP=78 Atk=84 Def=78 SpAtk=109 SpDef=85 Spd=100
BST: 534
Mapped Speed: 0.125
Mapped Health: 15.2 HP
Attack: 6.4 dmg, 32 tick cd, 3 projectile(s)
Ability: Flamme Intérieure (fire)
```

Les formules de conversion derrière ces valeurs sont dans
[le modèle de transformation](transforms.fr.md#des-statistiques-de-base-aux-valeurs-minecraft).
Cette sortie est construite avec `Text.literal` et n'est **pas** traduite : les
libellés restent en anglais dans les deux langues, et le nom affiché de la capacité reste
en français parce que l'enum le stocke ainsi.

| Message | Cause |
|---|---|
| `Espèce '<name>' introuvable` | absente de la liste des espèces résolues. Sans Cobblemon, tous les noms échouent |

---

## `/cshift transformnpc`

```mcfunction
/cshift transformnpc <pokemon [shiny] [form…]>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `pokemon` | chaîne gourmande | oui | même grammaire que `/cshift transform` |

Cible **l'entité que tu regardes**, à moins de 5 blocs, trouvée en lançant un rayon le
long de la ligne de regard de l'appelant contre les boîtes englobantes des entités,
élargies de 0,3. Il n'y a pas d'argument d'entité : tu vises, puis tu tapes. L'appelant
doit être un joueur, la commande ne peut donc pas tourner depuis un bloc de commande ni
depuis la console.

La cible doit être un EasyNPC. `noattacks` est analysé mais n'a aucun sens ici : les PNJ
ne lancent jamais de projectiles.

```mcfunction
/cshift transformnpc snorlax
```

```text
✦ PNJ transformé en snorlax
```

| Message | Cause |
|---|---|
| `Regardez un PNJ pour le transformer` | le rayon n'a rien trouvé à moins de 5 blocs |
| `Cette entité n'est pas un PNJ EasyNPC` | tu as touché une entité, mais `NpcTransformManager.isEasyNpc()` l'a rejetée |
| `Échec : espèce '<name>' introuvable` | la recherche d'espèce a échoué |

Les transformations de PNJ sont persistées, contrairement à celles des joueurs. Voir
[les transformations de PNJ](npc-transforms.fr.md).

---

## `/cshift revertnpc`

```mcfunction
/cshift revertnpc
```

Sans argument. Même rayon de 5 blocs. Annule la transformation du PNJ que tu regardes.

```text
✦ Transformation du PNJ annulée
```

| Message | Cause |
|---|---|
| `Regardez un PNJ pour annuler sa transformation` | rien dans le rayon |
| `Cette entité n'est pas un PNJ EasyNPC` | mauvais type d'entité |
| `Ce PNJ n'est pas transformé` | le PNJ n'a aucune transformation enregistrée |

---

## `/cshift revertallnpc`

```mcfunction
/cshift revertallnpc
```

Ni argument, ni rayon. Annule toutes les transformations de PNJ enregistrées et renvoie
leur nombre.

```text
✦ 3 transformation(s) NPC annulée(s)
```

---

## `/cshift scale` et `/cshift speed`

```mcfunction
/cshift scale <player> <value>
/cshift speed <player> <value>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `player` | sélecteur de joueur | oui | un joueur connecté, **déjà transformé** |
| `value` | flottant | oui | de `0.1` à `10.0`, imposé par Brigadier |

`scale` multiplie l'échelle automatique de l'espèce : le modificateur `GENERIC_SCALE`
final vaut `mapScale(height) × value`, donc `1.0` correspond à la taille propre de
l'espèce et `2.0` au double. La valeur est aussi poussée vers les clients pour que le
modèle rendu corresponde à la hitbox.

`speed` remplace la vitesse de déplacement dérivée de l'espèce par une base fixe de
`0.1 × value`. Passer `0` est le chemin de réinitialisation prévu dans le code et
restaure la valeur par défaut de l'espèce, mais la borne basse de Brigadier est `0.1` :
**la réinitialisation est inaccessible depuis la commande**. Seul un autre mod
appelant `setPlayerSpeed(uuid, 0)` peut la déclencher. La route `/speed` du dashboard
n'y change rien : elle ne vise que les PNJ et borne la valeur au même `[0.1, 10]`. En pratique, annule
la transformation puis transforme à nouveau.

```mcfunction
/cshift scale Steve 1.5
```

```text
✦ Scale de Steve → 1.5
```

```mcfunction
/cshift speed Steve 2.0
```

```text
✦ Vitesse de Steve → 2.0x
```

| Message | Cause |
|---|---|
| `<name> n'est pas transformé` | les deux sous-commandes exigent une transformation active. `speed` réutilise la clé de message de `scale`, si bien que le texte ne dit rien de la vitesse |

Aucune des deux valeurs ne survit à une annulation : elles vivent toutes deux dans
l'enregistrement `TransformData`, qui est jeté.

---

## `/cshift scalenpc` et `/cshift speednpc`

```mcfunction
/cshift scalenpc <value>
/cshift speednpc <value>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `value` | flottant | oui | de `0.1` à `10.0` |

Les équivalents pour les PNJ, visés par le même rayon de 5 blocs. `speednpc` multiplie
l'attribut de vitesse de déplacement du PNJ.

```text
✦ Scale du PNJ → 1.5
✦ Vitesse du PNJ → 2.0x
```

| Message | Cause |
|---|---|
| `Regardez un PNJ pour modifier son scale` / `…sa vitesse` | rien dans le rayon |
| `Ce PNJ n'est pas transformé` | la cible n'a aucune transformation enregistrée. Contrairement à `transformnpc`, ces deux-là ne vérifient que ce point, pas que l'entité soit un EasyNPC |

---

## Piloter le mod depuis ailleurs

Chaque transformation ci-dessus est accessible sans commande :

- **Dashboard** : le module `shift`, ou directement les endpoints REST. Voir
  [l'API du dashboard](dashboard.fr.md).
- **Événements CheeseGuard** : les types d'effet `TRANSFORM_ALL`, `TRANSFORM_RANDOM`,
  `TRANSFORM_BY_TEAM` et `TRANSFORM_REVERT_ALL` transforment d'un coup tous les participants d'un
  événement chronométré.
- **CheeseKart** : transforme un pilote quand il est mis en kart et annule sa
  transformation à la fin de la session.

Ces deux derniers passent par `getTransformManager().transform(...)` et `revert(...)`
par réflexion. [L'architecture](architecture.fr.md#lapi-externe-de-fait) explique
pourquoi cela rend ces deux signatures de méthode porteuses.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
