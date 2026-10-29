[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](abilities.md)

# Capacités passives

Les capacités passives sont la moitié de CobbleShift qui ne concerne pas la
transformation. Le joueur garde son propre corps et son propre skin ; ce qui change,
c'est que le Pokémon du premier emplacement de son équipe lui accorde un effet de statut
permanent, assorti à son type. Garde un Dracaufeu sur toi et tu ne brûles plus. Garde un
Ectoplasma et tu deviens invisible. Change de Pokémon de tête et le bonus suit en
quelques secondes.

Le sous-système est **désactivé par défaut** : `abilityGlobalEnabled` vaut `false` dans
une config neuve. Active-le avec `/cshift ability global enable`, qui écrit aussi le
drapeau sur le disque.

---

## Le modèle

Trois éléments d'état, tous détenus par `AbilityManager` :

| État | Contenu | Persisté |
|---|---|---|
| `activeAbilities` | un `AbilityData` par joueur : espèce, les deux types, un drapeau `forced`, l'horodatage d'activation | non |
| `disabledPlayers` | les UUID exclus, positionnés par `/cshift ability toggle` | non |
| `abilityGlobalEnabled` | l'interrupteur général | **oui**, dans `config/cobbleshift.json` |

Seul l'interrupteur général survit à un redémarrage. Les exclusions par joueur et les
capacités actives sont reconstruites depuis l'équipe au tick de rafraîchissement suivant,
ce qui est en général ce que tu veux : rien à nettoyer après un crash.

Une capacité peut être dans l'un de deux modes :

- **auto** : lue sur le Pokémon de tête du joueur à chaque rafraîchissement. C'est le
  mode par défaut.
- **forcé** : fixée sur une espèce par un administrateur avec
  `/cshift ability <player> <pokemon>`. Une entrée forcée saute entièrement la lecture
  de l'équipe : elle survit donc à tout changement d'équipe jusqu'à ce qu'on la lève.

## La boucle de rafraîchissement

`AbilityManager.tick()` s'exécute à chaque tick serveur, mais repart aussitôt sauf si
`tickCounter % abilityTickInterval == 0`. Avec la valeur par défaut de
`abilityTickInterval`, 100, cela fait une fois toutes les cinq secondes. À un tick de
rafraîchissement :

1. Si `abilityGlobalEnabled` est faux, on s'arrête là. Rien n'est effacé : les effets
   déjà posés sur les joueurs expirent d'eux-mêmes.
2. Pour chaque joueur connecté : on le saute si son UUID est dans `disabledPlayers`.
3. Si son entrée est forcée, on réapplique ses effets et on passe au suivant.
4. Sinon, en mode auto, on lit le Pokémon de tête et on met l'entrée à jour si l'espèce
   a changé. Si la lecture de l'équipe ne renvoie rien, on supprime l'entrée et on
   demande au client d'effacer le HUD.
5. On réapplique les effets de l'entrée qui existe à ce stade.

Les effets sont appliqués pour une durée de `abilityEffectDuration` ticks, 160 par
défaut, soit 8 secondes : volontairement plus long que l'intervalle de rafraîchissement
de 5 secondes, pour que le bonus ne clignote jamais entre deux rafraîchissements. Si tu
augmentes `abilityTickInterval`, tu dois augmenter `abilityEffectDuration` en même temps,
sinon les joueurs verront leur bonus disparaître puis revenir. Les mutateurs de la config
imposent des planchers de 20 et 40 ticks respectivement, mais n'imposent **pas** cette
relation entre les deux.

Chaque effet appliqué est marqué ambiant, sans particules et avec l'icône du HUD. C'est le
caractère ambiant qui fait afficher l'effet avec l'icône discrète façon balise plutôt
qu'avec celle d'un effet appliqué par le joueur, et c'est aussi ainsi que
`cleanupStaleTransform` reconnaît l'Invisibilité propre au mod.

## Le tableau des effets

Lu directement dans `AbilityType`. Les noms affichés sont stockés en français dans
l'enum et ne sont **pas** traduits à l'exécution : ils sont identiques dans les deux
langues.

| Type | Nom affiché | Effets accordés |
|---|---|---|
| `fire` | Flamme Intérieure | Résistance au feu I |
| `water` | Nage Rapide | Apnée I + Grâce du dauphin I |
| `grass` | Photosynthèse | Régénération I |
| `electric` | Charge Statique | Vitesse I |
| `ice` | Coeur de Glace | Résistance I |
| `flying` | Plume Légère | Chute lente I |
| `ground` | Ancrage | Résistance I |
| `rock` | Peau de Pierre | Résistance I |
| `steel` | Blindage | Résistance I + Résistance au feu I |
| `poison` | Immunité Toxique | **rien d'utilisable** (voir plus bas) |
| `psychic` | Perception Extra | Surbrillance I |
| `ghost` | Ombre Furtive | Invisibilité I |
| `dark` | Vision Nocturne | Vision nocturne I |
| `dragon` | Fureur Draconique | Force I |
| `fairy` | Grâce Féerique | Régénération I + Chance I |
| `fighting` | Esprit Combatif | Force I + Vitesse I |
| `bug` | Agilité Insecte | Saut amélioré I |
| `normal` | Endurance | aucun : la branche `NORMAL` de l'enum est vide |

Chaque effet a un amplificateur de 0, donc toujours le niveau I. Rien ne varie selon les
statistiques de base, le BST ou le niveau : le bonus Insecte d'une Chenipan est identique
à celui d'un Cizayox.

### Les doubles types se cumulent

Un Pokémon à double type reçoit les listes d'effets de ses deux types. Cizayox est
`bug/steel` : Saut amélioré, Résistance et Résistance au feu. Quand les deux types
accordent le même effet, le second appel ne fait rien à amplificateur et durée égaux. Il
n'y a pas de limite au nombre d'effets qu'un double type peut cumuler, parce qu'aucun
type n'en accorde plus de deux.

### `poison` et `normal` n'accordent rien

`NORMAL` est explicitement vide : la branche de l'enum est `{}`.

`POISON`, en revanche, est un bug et non un choix de conception. La branche ajoute
`new StatusEffectInstance(StatusEffects.POISON, 1, 0, ...)`, un Poison d'un tick, avec un
commentaire affirmant qu'il dissipe un poison existant. Ce n'est pas le cas :
`addStatusEffect` en vanilla ne remplace une instance existante que si la nouvelle est
plus forte ou plus longue, si bien qu'un Poison d'un tick ne peut pas en déloger un vrai,
et sur un joueur non empoisonné il ne fait rien pendant un tick. Un Pokémon de tête de
type Poison n'apporte actuellement aucun avantage. Le corriger implique de retirer
l'effet plutôt que de le rajouter, ce qui change le comportement ; il est donc laissé
tel quel et documenté ici.

### Spectre est un piège sur un serveur public

`ghost` accorde une vraie Invisibilité, rafraîchie toutes les cinq secondes tant que le
joueur garde un Pokémon de tête de type Spectre. Sur un serveur PvP, c'est une
invisibilité permanente à la portée de quiconque attrape un Fantominus. Laisse le
sous-système désactivé, ou exclus ces joueurs avec `/cshift ability toggle`.

Le type interagit aussi avec les transformations : les deux utilisent l'Invisibilité, et
une annulation appelle `removeStatusEffect(StatusEffects.INVISIBILITY)` sans condition.
Annule la transformation d'un joueur qui a un Spectre en tête et l'invisibilité de sa
capacité part avec celle de la transformation, jusqu'à ce que le tick de rafraîchissement
suivant la rétablisse. Pendant cinq secondes au plus, il est visible.

## Lire le Pokémon de tête

`getLeadPokemon()` parcourt Cobblemon par réflexion, avec une solution de repli à chaque
étape :

1. `com.cobblemon.mod.common.Cobblemon.INSTANCE` → `getStorage()`.
2. `getParty(ServerPlayerEntity)`, avec repli sur `getParty(UUID)`. Si l'appel renvoie un
   `CompletableFuture`, le code prend `getNow(null)` : un coup d'œil non bloquant, qui
   renvoie `null` quand l'équipe n'a pas fini de se charger. Un joueur qui vient de se
   connecter peut donc passer un cycle de rafraîchissement supplémentaire sans capacité.
3. L'emplacement 0 via `get(int)`, puis `getSlot(int)`, puis le premier élément de
   l'itérateur.
4. `getSpecies()`, puis le nom de l'espèce via `getName()` → `getPath()`, avec repli sur
   `showdownId()`.
5. `getPrimaryType()` et `getSecondaryType()`, chacun passé dans `extractTypeName()`.

Toute `ClassNotFoundException` renvoie `null` en silence : c'est le chemin « Cobblemon
absent ». Tout autre échec est journalisé au niveau debug et renvoie `null`, ce qui est
indiscernable vu de l'extérieur. Pourquoi ces noms de méthode précis peuvent être codés en
dur sans risque, et lesquels ne le peuvent pas, est expliqué dans
[l'architecture](architecture.fr.md#la-réflexion-et-pourquoi-les-noms-de-méthode-comptent).

`extractTypeName()` essaie `getName()`, `getDisplayName()` et `name()` dans cet ordre,
puis un champ déclaré `name`, puis `toString()` en minuscules. Si tout cela produit autre
chose qu'un identifiant de type connu, `AbilityType.fromTypeName()` retombe sur `NORMAL` :
un changement d'API de Cobblemon ne fait donc pas planter la boucle, il réduit en silence
toutes les capacités à rien. Ce mode d'échec mérite d'être retenu : du `NORMAL` silencieux
partout signifie que l'extraction des types est cassée, pas que ta config est fausse.

## Le HUD client

`AbilityHudRenderer` dessine la capacité active côté client, alimenté par
`AbilitySyncPayload` (espèce, `type1`, `type2`). Le serveur en envoie un à chaque
changement : nouvelle capacité automatique, forçage, levée du forçage, basculement par
joueur, et un payload vide par joueur quand `deactivateAllAndSync()` s'exécute. Une chaîne
d'espèce vide est le signal pour masquer le HUD.

La synchronisation est protégée par `ServerPlayNetworking.canSend` : un client vanilla ne
la reçoit jamais. Les effets s'appliquent quand même ; seul l'affichage manque.

## Piloter les capacités en administrateur

| Action | Commande | Dashboard |
|---|---|---|
| activer ou désactiver le sous-système | `/cshift ability global enable\|disable` | `PUT /api/v1/shift/config` |
| fixer un joueur sur une espèce | `/cshift ability <player> <pokemon>` | — |
| lever ce forçage | `/cshift ability <player>` | — |
| exclure un joueur | `/cshift ability toggle <player>` | — |
| voir ce qui est actif | `/cshift list` | `GET /api/v1/shift/abilities` |

`forceAbility` ne valide pas l'espèce : un nom inconnu stocke `normal` comme type et le
joueur n'obtient rien. `/cshift list` marque les entrées forcées `[FORCED]` mais n'affiche
que l'espèce et le type, jamais le nom du joueur, ce qui rend l'audit des forçages plus
difficile qu'il ne devrait l'être.

---

← [Transformations](transforms.fr.md) · [Transformations de PNJ](npc-transforms.fr.md) →
