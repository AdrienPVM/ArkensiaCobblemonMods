[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Prise en main

À la fin de cette page tu auras un serveur où les Pokémon d'un joueur s'arrêtent au niveau
20, un champion d'arène rctmod qui relève ce plafond à 30 quand il est battu, et un badge de
limite fonctionnel à l'écran. Un champion, une marche de limite, vérifié en jeu.

Le parcours suppose un serveur Fabric 1.21.1 avec Cobblemon, Radical Cobblemon Trainers et
CobbleCap installés, comme décrit dans [installation](installation.fr.md). Il ne suppose
**pas** CheeseDash — tout se fait ici avec des commandes.

---

## 1. Vérifier que le mod est réveillé

Démarre le serveur et cherche :

```text
[CobbleCap] Initialized — startingCap=20, enabled=true, champions=0
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

`champions=0` est attendu : le mod est livré avec une liste de champions vide. Les deux
lignes de hook sont les importantes — elles prouvent que l'abonnement par réflexion au bus
d'événements de Cobblemon a abouti. Si tu vois plutôt `Cobblemon not found` ou
`Could not find XP event field`, arrête-toi ici et lis
[le dépannage](troubleshooting.fr.md#la-limite-dxp-ne-fait-rien).

Connecte-toi. Le log doit afficher :

```text
[CobbleCap] Player joined: Steve (cap=20)
```

Cette ligne signifie qu'une entrée a été créée dans `config/cobblecap/players.json` avec le
`startingCap` par défaut.

## 2. Voir la limite du point de vue du joueur

```mcfunction
/cobblecap
```

```text
Votre limite de niveau : 20
Tous les champions ont été vaincus !
```

La seconde ligne n'est pas un bug. `nextUnbeaten` sur une liste de champions vide ne renvoie
rien, et le mod rapporte cela comme « il ne reste plus rien à battre ». Elle deviendra un
vrai nom de champion à l'étape 5.

Si CobbleCap est aussi sur ton client, un petit badge affichant `20` occupe le coin
inférieur droit. Déplace-le s'il gêne :

```mcfunction
/cobblecap hud position top-left
```

```text
HUD déplacé vers top-left.
```

## 3. Voir la limite mordre pour de vrai

Amène un Pokémon au niveau 20. Le plus rapide est une poignée de bonbons exp, ou :

```mcfunction
/pokegive Steve pikachu level=19
```

Puis fais-le combattre, ou donne-lui un bonbon. Dès qu'il atteint la limite, l'expérience
suivante est refusée et le joueur reçoit une ligne dans la barre d'action :

```text
Pikachu ne peut pas gagner d'XP — limite de niveau : 20. Tous les champions ont été vaincus !
```

Deux détails à remarquer, parce qu'ils résument toute la conception :

- Le Pokémon reste au **niveau 19** avec une barre d'XP presque pleine, pas au niveau 20. La
  couche de rognage le laisse volontairement à un point d'expérience du niveau plafond pour
  que le chemin de montée de niveau de Cobblemon ne se déclenche jamais. Voir
  [la limite de niveau](level-cap.fr.md#2-pokemonaddexperiencemixin--la-couche-de-rognage).
- L'expérience excédentaire est **jetée**, pas mise de côté. Relever la limite plus tard ne
  fait pas gagner plusieurs niveaux d'un coup au Pokémon.

Le message est limité à un par 30 secondes et par Pokémon : ne l'attends pas à chaque bonbon.

## 4. Choisir le dresseur qui sera ton premier champion

CobbleCap identifie un champion par son **identifiant de dresseur rctmod**, et il te faut la
chaîne exacte. Place ou trouve un PNJ de dresseur rctmod, fais un clic droit dessus et lis
le log serveur :

```text
[CobbleCap] BATTLE_PRE: entity=TrainerMob trainerId=gym_leader_pierre_0395 (raw=gym_leader_pierre_0395)
```

Cette ligne n'apparaît que lorsqu'un combat de champion est en cours d'évaluation : sur une
installation neuve tu ne verras peut-être rien. La solution fiable est de lire
l'identifiant dans ton datapack rctmod — le nom de fichier sous `data/rctmod/trainers/`
*est* l'identifiant.

Note-le. Tout l'étape suivante dépend de l'avoir au caractère près.

## 5. Rédiger un champion

Arrête le serveur, ou tiens-toi prêt à lancer `/cobblecap reload`. Ouvre
`config/cobblecap/champions.json` et remplace le tableau vide :

```json
{
  "champions": [
    {
      "id": "gym_leader_pierre_0395",
      "displayName": "Pierre",
      "capGranted": 30,
      "allowRefight": false
    }
  ]
}
```

Quatre champs. Tous les autres retombent sur une valeur par défaut — pas de condition de
Pokédex, pas de condition de quête, pas de condition de scoreboard, pas de variante défi.
Tu les ajouteras plus tard ; commence par un champion qui marche.

Recharge :

```mcfunction
/cobblecap reload
```

```text
Config champions rechargée (1 champions chargés).
```

⚠️ Un compte de `0` signifie que le JSON n'a pas pu être analysé et que l'ancienne liste est
toujours en mémoire. Cherche `[CobbleCap] Failed to load champions.json` dans le log.

Redemande maintenant côté joueur :

```mcfunction
/cobblecap
```

```text
Votre limite de niveau : 20
Prochain champion : Pierre
```

Le badge affiche toujours `20`, et `reload` a resynchronisé tous les clients pour que le nom
du prochain champion soit à jour.

## 6. Vérifier la condition côté admin

Avant de combattre, regarde ce que le serveur pense :

```mcfunction
/cobblecap status Steve
```

Le bloc intéressant est au milieu :

```text
─── Champions (0/1 beaten) ───
  ✗ gym_leader_pierre_0395 (Pierre, cap=30)
─── Next champion gating ───
Next: gym_leader_pierre_0395 (Pierre)
  cap granted: 30 | allowRefight: false
  victoryQuestId:
  challengeId: ''
  scoreboard: (none)
  ftb quest: (none)
  pokedex: (none)
```

Trois lignes `(none)` signifient qu'il n'y a aucune condition, donc le combat est autorisé.
Si l'identifiant que tu as tapé ne correspond pas au dresseur, c'est ici que tu le
remarqueras : le champion est listé mais le PNJ ne déclenche rien de tout cela.

## 7. Battre le champion

Clic droit sur le dresseur et gagne le combat. Tout le serveur voit :

```text
Steve a vaincu le champion Pierre ! Sa limite est maintenant 30.
```

et le joueur reçoit en plus :

```text
Vous avez vaincu Pierre ! Votre limite de niveau est maintenant 30.
```

Le badge passe à `30` sans reconnexion — `handleVictory` appelle `syncToClient` à la fin.
`/cobblecap` annonce de nouveau `Tous les champions ont été vaincus !`, puisqu'il n'y a qu'un
seul champion dans la liste.

Confirme que c'est bien enregistré :

```mcfunction
/cobblecap get Steve
```

```text
Limite actuelle de Steve : 30.
```

Le Pikachu de l'étape 3 reprend maintenant sa progression depuis 19 — depuis là où il s'est
arrêté, pas depuis un stock accumulé.

Si la victoire n'a produit aucun message, l'identifiant du dresseur ne correspond pas.
Débloque-toi à la main puis corrige l'identifiant :

```mcfunction
/cobblecap addbeaten Steve "gym_leader_pierre_0395"
```

```text
Added 'gym_leader_pierre_0395' to Steve's beaten list — cap raised to 30.
```

## 8. Ajouter un second champion et une condition

Ajoute au tableau — rappelle-toi que l'ordre **est** la progression :

```json
{
  "champions": [
    {
      "id": "gym_leader_pierre_0395",
      "displayName": "Pierre",
      "capGranted": 30,
      "allowRefight": false
    },
    {
      "id": "gym_leader_ondine_0396",
      "displayName": "Ondine",
      "capGranted": 40,
      "requiredPokedex": 15,
      "allowRefight": false
    }
  ]
}
```

`/cobblecap reload`, puis essaie d'affronter Ondine avec moins de 15 Pokémon capturés :

```text
Vous ne pouvez pas défier Ondine — conditions non remplies :
  - Pokédex : 8/15 capturés
```

Et essaie d'affronter Ondine *avant* Pierre, depuis un joueur neuf, pour voir la condition
d'ordre :

```text
Vous ne pouvez pas défier Ondine — vous devez d'abord vaincre Pierre.
```

Ces deux refus sont la colonne vertébrale de la progression : les conditions, puis l'ordre.

## 9. Réinitialiser et recommencer

```mcfunction
/cobblecap reset Steve
```

```text
Limite de Steve réinitialisée à 20 (valeur de départ).
```

C'est un effacement complet, pas seulement le nombre : champions battus, défis battus,
cooldowns et blocage d'XP sont tous remis à zéro, et pour chaque champion de la liste le mod
révoque les advancements `cobblecap:champion/<clé>` et `cobblecap:challenge/<clé>` et
réinitialise les quêtes FTB. Il n'y a ni confirmation ni retour en arrière. Utilise-le sur
un compte de test.

---

## Où aller ensuite

| Tu veux | Lis |
|---|---|
| les variantes défi, les quêtes, les conditions de scoreboard, le Panthéon | [le système de champion](champions.fr.md) |
| pourquoi la limite se comporte ainsi, et ce que `enabled: false` coupe vraiment | [la limite de niveau](level-cap.fr.md) |
| des statues et dioramas de tes champions | [statues et dioramas](statues.fr.md) |
| le champion final reprenant l'équipe du dernier vainqueur | [le morphing du champion](champion-morphing.fr.md) — lis d'abord les avertissements |
| chaque sous-commande en détail | [commandes](commands.fr.md) et [commandes statues, chrono et champion](commands-statues.fr.md) |
| le panneau d'administration | [l'API dashboard](dashboard.fr.md) |

⚠️ Une chose à régler avant d'aller plus loin : sur tout serveur qui n'est pas Arkensia, le
morph du champion ne peut pas fonctionner tant que tu n'as pas changé `datapackName` dans
`RctTrainerMorpher.java` ligne 57 et reconstruit. Tout ce qui est sur cette page fonctionne
sans cette modification ; le morphing du champion et les dioramas dynamiques, non.

---

← [Installation](installation.fr.md) · [La limite de niveau](level-cap.fr.md) →
