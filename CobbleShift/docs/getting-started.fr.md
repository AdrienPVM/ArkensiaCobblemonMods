[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de ce parcours, tu auras changé un joueur en Dracaufeu qui se déplace à la
bonne vitesse, frappe avec les bons dégâts et lance des boules de feu au clic gauche ;
tu auras donné à un deuxième joueur un bonus passif piloté par son équipe ; et tu auras
transformé un PNJ qui survit à un redémarrage. Compte vingt minutes sur un serveur que tu
peux redémarrer.

Le point de départ est un serveur Fabric 1.21.1 avec Cobblemon, le jar CobbleShift dans
`mods/` des deux côtés, et un compte au niveau de permission 2. Si tu n'en es pas là,
[l'installation](installation.fr.md) t'y amène.

---

## 1. Vérifier que Cobblemon est joignable

Tout, dans ce mod, résout une espèce par réflexion dans Cobblemon. Vérifie que ça
fonctionne avant toute chose.

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

De vraies statistiques signifient que la réflexion a trouvé le registre des espèces de
Cobblemon. `Espèce 'charizard' introuvable` signifie qu'elle ne l'a pas trouvé, et rien
de ce qui suit ne fonctionnera : passe d'abord par [le dépannage](troubleshooting.fr.md).

Ces valeurs dérivées sont ce que la transformation fera ressentir. `Mapped Speed: 0.125`
face au `0.1` normal d'un joueur, c'est un quart plus rapide ; `15.2 HP` est un peu en
dessous des vingt d'un joueur. Lis cette sortie pour n'importe quelle espèce avant de la
confier à un joueur.

## 2. Transformer quelqu'un

```mcfunction
/cshift transform Steve charizard
```

Tu vois :

```text
✦ Steve transformé en charizard
```

Steve voit `✦ Vous êtes transformé en charizard !` et, sur son écran :

- la caméra bascule en troisième personne et F5 ne fait plus rien
- un modèle de Dracaufeu à la place de son modèle de joueur
- ni item en main, ni bras, ni nom au-dessus de la tête, et une simple croix blanche dessinée
  par le mod à la place du réticule vanilla
- un cri de Dracaufeu, s'il se trouve à moins de 32 blocs de lui-même, ce qui est le cas

Tous les autres voient aussi un Dracaufeu, à condition d'avoir le mod. Un client vanilla
ne voit rien du tout : Steve lui est invisible, parce que l'invisibilité est le mécanisme
de base et que le modèle est une couche ajoutée par le mod par-dessus.

## 3. Observer l'attaque

Steve fait un clic gauche. Le coup vanilla n'a jamais lieu ; `AttackInputMixin` l'annule
et le client envoie un `AttackAbilityPayload` à la place. Environ 1,3 seconde plus tard,
trois boules de feu quittent le modèle dans un cône de 15° (trois, parce que le total
des statistiques de base de Dracaufeu, 534, dépasse le seuil de 450), et tout ce
qu'elles touchent brûle pendant trois secondes.

Le temps de préparation est voulu : le tir est mis en file pour 26 ticks afin que
l'animation d'attaque du modèle ait le temps de se jouer, et la direction de visée est
lue **au moment de l'apparition** du projectile, ce qui permet à Steve de réajuster sa
visée pendant l'animation. Le cooldown, lui, a été posé au moment du clic : le délai
d'animation ne lui coûte donc jamais de dégâts par seconde.

Si tu n'en veux pas, passe `noattacks` au moment de la transformation :

```mcfunction
/cshift transform Steve charizard noattacks
```

```text
✦ Steve transformé en charizard [no attacks]
```

Impossible de changer ce réglage sur une transformation en cours. Transforme à nouveau
pour le modifier.

## 4. Redimensionner et changer la vitesse à la main

L'échelle dérivée de l'espèce n'est souvent pas celle que veut un mini-jeu.

```mcfunction
/cshift scale Steve 1.5
```

```text
✦ Scale de Steve → 1.5
```

La valeur multiplie l'échelle automatique de l'espèce : `1.0` correspond à la taille
propre de Dracaufeu. Elle est aussi envoyée aux clients, si bien que le modèle dessiné et
la boîte de collision restent d'accord.

```mcfunction
/cshift speed Steve 2.0
```

```text
✦ Vitesse de Steve → 2.0x
```

La vitesse **remplace** la valeur dérivée de l'espèce par une base fixe de
`0.1 × valeur` au lieu de la multiplier. Aucune des deux valeurs ne survit à une
annulation.

## 5. Ajouter le chromatique et une forme régionale

L'argument d'espèce est gourmand et le mod analyse lui-même le reste de la ligne.
L'espèce vient en premier ; tout ce qui suit est un drapeau ou un aspect Cobblemon, dans
n'importe quel ordre.

```mcfunction
/cshift transform Steve lycanroc shiny midnight
```

```text
✦ Steve transformé en lycanroc shiny midnight
```

C'est l'écho qui compte : il montre ce que le mod a réellement analysé, et c'est le
moyen le moins coûteux de savoir si ton mot a produit l'aspect voulu. La complétion par
tabulation propose `shiny`, `noattacks` et six noms de forme, mais tu peux taper
n'importe quelle chaîne d'aspect acceptée par Cobblemon.

Puis rends à Steve son apparence :

```mcfunction
/cshift revert Steve
```

```text
✦ Steve — transformation annulée
```

Il entend une célébration de villageois, voit des particules et retrouve son propre
corps. Les trois modificateurs d'attribut sont retirés et sa santé est ramenée au
nouveau maximum si la transformation l'avait relevé.

## 6. Activer les capacités passives

C'est l'autre moitié du mod, désactivée dans une config neuve.

```mcfunction
/cshift ability global enable
```

```text
✦ Abilities globales activées
```

C'est la seule sous-commande qui écrit le fichier de configuration : l'interrupteur survit
donc à un redémarrage.

Demande maintenant à Alex de mettre un Ectoplasma dans le premier emplacement de son
équipe et attends. En moins de cinq secondes (`abilityTickInterval` vaut 100 ticks par
défaut), elle devient invisible et le HUD des capacités apparaît. Remplace son Pokémon
de tête par un Dracaufeu et, au rafraîchissement suivant, elle reçoit Résistance au feu ;
l'invisibilité n'est plus rafraîchie et s'éteint en `abilityEffectDuration` (huit secondes
par défaut).

⚠️ L'effet du type Spectre est une vraie Invisibilité et peut donner un avantage en PvP. Sur
un serveur PvP, tranche la question
avant de laisser le sous-système actif. Le tableau complet des types se trouve dans
[les capacités passives](abilities.fr.md).

Pour fixer un joueur sur un type quelle que soit son équipe :

```mcfunction
/cshift ability Alex charizard
```

```text
✦ Ability forcée pour Alex → charizard
```

Et pour lever ce forçage :

```mcfunction
/cshift ability Alex
```

```text
✦ Ability forcée retirée pour Alex — retour mode auto
```

## 7. Inspecter ce qui tourne

```mcfunction
/cshift list
```

```text
═══ CobbleShift Status ═══
Transforms actives : 0
Abilities actives : 1
  • charizard (fire)
Abilities globales : ON
```

Aucune transformation n'apparaît, puisque tu as ramené Steve à l'étape 5, et la capacité
d'Alex n'est plus marquée `[FORCED]` puisque tu as levé le forçage. Les lignes de capacités
ne portent aucun nom de joueur : l'enregistrement contient un
UUID et la commande ne le résout jamais. Si tu as besoin du nom, lis plutôt
`GET /api/v1/shift/abilities`, qui fait la jointure pour toi.

## 8. Transformer un PNJ, puis redémarrer

Les transformations de PNJ sont purement visuelles et persistent, ce qui est toute la
raison d'être de ce sous-système séparé.

Place-toi à moins de 5 blocs d'un EasyNPC, regarde-le et tape :

```mcfunction
/cshift transformnpc snorlax
```

```text
✦ PNJ transformé en snorlax
```

Il n'y a pas d'argument d'entité : tu vises, puis tu tapes. La commande lance un rayon
le long de ta ligne de regard contre les boîtes englobantes des entités, élargies de
0,3, et retient le premier impact.

Arrête maintenant le serveur et redémarre-le. Le log affiche :

```text
[CobbleShift] Loaded 1 NPC transform(s), will re-apply on first tick
```

Le Ronflex est toujours un Ronflex. Un joueur encore transformé à ce moment-là aurait, lui,
retrouvé son apparence : `SERVER_STOPPING` annule toutes les transformations de joueur. Les transformations de joueur sont un état
de session ; celles de PNJ sont sauvegardées. La différence et ses conséquences sont
décrites dans [les transformations de PNJ](npc-transforms.fr.md).

## 9. Régler depuis le dashboard

CheeseDash lancé, connecte-toi sur le port **44311** et ouvre l'entrée **CobbleShift**
dans la barre latérale. Tu y trouves la liste des transformations en direct, la liste
des capacités avec les noms de joueurs résolus, la sonde d'espèce et le formulaire de
config.

Deux choses à savoir avant de t'y fier. Le `PUT` de la config exige une session
`SUPER_ADMIN` et non `OPERATOR`, parce qu'il écrit sur le disque. Et le champ
`mappedScale` de `GET /species/:name` est faux : il injecte la taille du Pokédex en
décimètres dans une formule qui attend des blocs, si bien que presque toutes les espèces
affichent le plafond de `2.5`. Utilise `/cshift species` en jeu pour cette valeur. Les
deux points sont détaillés dans [l'API du dashboard](dashboard.fr.md).

## Pour aller plus loin

| Tu veux | Lis |
|---|---|
| chaque sous-commande, chaque argument et chaque message d'échec | [Commandes](commands.fr.md) |
| ce que change réellement chaque clé de config | [Configuration](configuration.fr.md) |
| ce qu'applique une transformation, et ce qu'elle coûte | [Transformations](transforms.fr.md) |
| les effets passifs par type, un par un | [Capacités passives](abilities.fr.md) |
| les transformations de PNJ et leur persistance | [Transformations de PNJ](npc-transforms.fr.md) |
| les routes REST et leurs rôles | [API du dashboard](dashboard.fr.md) |
| la réflexion, le threading, les points faibles connus | [Architecture](architecture.fr.md) |

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
