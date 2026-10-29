[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette marche à suivre, tu auras une zone protégée où personne d'autre que ses
membres ne peut construire, une seconde zone groupée avec la première pour qu'elles
partagent les mêmes règles, une copie sauvegardée des blocs de la première zone que tu
peux restaurer à la demande, et une prison fonctionnelle. Tout se fait en jeu, en
commandes, pour que tu voies ce que chaque pièce fait avant de toucher au dashboard.

Compte vingt minutes. Il te faut un serveur avec CheeseDash et CheeseGuard installés —
voir [l'installation](installation.fr.md) — et le niveau d'opérateur 2.

---

## 1. Vérifier que le mod répond

```mcfunction
/cg
```

```text
[CheeseGuard] Administration disponible sur le dashboard web CheeseDash.
```

Si tu obtiens *Unknown or incomplete command*, le jar n'est pas sur le serveur. La commande
racine est `/cg` ; il n'y a pas de `/cheeseguard`.

## 2. Délimiter une zone

Récupère l'outil de sélection :

```mcfunction
/cg wand
```

```text
Zone wand received!
```

Une hache en or nommée *Zone Selection Wand* arrive dans ton inventaire. Clic gauche sur un
coin de la zone que tu veux protéger, clic droit sur le coin opposé. Choisis petit pour
l'instant — une boîte de 20×10×20 suffit largement.

Si tu préfères te passer du wand, place-toi à un coin et lance `/cg pos1`, puis à l'autre et
lance `/cg pos2` :

```text
Position 1 : 118, 64, -302
```

```text
Position 2 : 142, 90, -276
```

Les deux coins doivent être dans le même monde, et la sélection ne vit qu'en mémoire — un
redémarrage l'efface.

## 3. Créer la zone

```mcfunction
/cg create Market Square
```

```text
Région 'Market Square' créée !
```

Le nom peut contenir des espaces ; l'**identifiant** en dérive, en minuscules et avec tout
caractère hors de `[a-z0-9_-]` remplacé par `_` : l'identifiant de cette zone est donc
`market_square`. C'est lui que tu utiliseras dans toutes les autres commandes.

Vérifie :

```mcfunction
/cg info market_square
```

```text
Market Square
ID : market_square | Overworld
Min : 118,64,-302
Max : 142,90,-276
Groupe : aucun
Priorité : 0 | Membres : 1
```

`Membres : 1`, c'est toi — le créateur est ajouté automatiquement. `Groupe : aucun` est le
repli codé en dur pour « pas de groupe » ; tu changeras cela à l'étape 7.

Cette écriture a créé `config/cheeseguard/regions.json`.

## 4. Voir la zone

```mcfunction
/cg toggle
```

```text
Visualisation : Activée
```

Des particules dessinent maintenant le contour des zones proches, pour toi seul. Longe la
limite et vérifie que c'est bien la boîte que tu voulais. Relance la commande pour couper.
Si la boîte est fausse, supprime et recommence — `/cg delete market_square`, puis retour à
l'étape 2.

## 5. La protéger

Rien n'est encore protégé. Une zone sans aucun flag posé se comporte exactement comme du
monde libre, parce qu'un flag non posé signifie *hérité*, et que le défaut hérité pour
`block-break` est « autorisé ».

```mcfunction
/cg flag market_square block-break false
```

```text
Flag block-break = false
```

```mcfunction
/cg flag market_square block-place false
```

```mcfunction
/cg flag market_square pvp false
```

Connecte-toi maintenant avec un joueur qui n'est **pas** membre, ou demande à quelqu'un, et
essaie de casser un bloc dans la boîte. Rien ne se passe. Toi, en tant que membre, peux
toujours construire : `block-break` est l'un des flags dont les membres sont exemptés — le
comportement classique d'une protection.

⚠️ Deux choses à ne pas rater ici. La valeur doit être exactement `true`, `false` ou
`inherit` : toute autre valeur, `yes` et `1` compris, est lue comme `false`, avec un
message de confirmation qui ressemble à une réussite. Et `/cg flag` était **sans garde de
permission jusqu'au 29 octobre 2026** — sur un jar plus ancien, n'importe quel joueur peut
la lancer sur n'importe quelle zone dont il devine l'identifiant. Compare
[le tableau des gardes](commands.fr.md#niveaux-de-permission) avec le jar que tu as
déployé.

Les 31 flags, et l'exemption des membres pour chacun, sont listés dans
[les commandes](commands.fr.md#flags) ; ce que chacun gouverne réellement est dans
[zones et flags](zones-and-flags.fr.md).

## 6. Ajouter un membre

Quelqu'un d'autre a besoin de construire ici. Il doit être connecté :

```mcfunction
/cg addmember market_square Steve
```

```text
Steve ajouté.
```

Steve peut désormais casser et poser dans la zone. Vérifie avec `/cg info market_square` —
le nombre de membres passe à 2.

Pour le retirer, `/cg removemember market_square Steve`. Attention : cette commande signale
une réussite qu'elle ait retiré quelque chose ou non, donc revérifie toujours le compte.

## 7. Grouper deux zones pour qu'elles partagent leurs règles

Crée une seconde zone de la même façon — sélection, puis :

```mcfunction
/cg create Market Annex
```

Plutôt que de reposer les mêmes trois flags, mets les deux zones dans un groupe et pose les
flags une seule fois sur le groupe. Crée-le :

```mcfunction
/cg group create Public Areas
```

```text
Groupe créé : Public Areas
```

Affecte les deux zones :

```mcfunction
/cg group add market_square public_areas
```

```text
Market Square → groupe Public Areas
```

```mcfunction
/cg group add market_annex public_areas
```

Une zone résout un flag qu'elle ne pose pas elle-même depuis son groupe, et seulement
ensuite depuis le défaut du flag. Pour que les règles du groupe soient celles qui
s'appliquent, efface donc les valeurs par zone posées à l'étape 5 :

```mcfunction
/cg flag market_square block-break inherit
```

Les flags du groupe, eux, se posent depuis le dashboard, pas par commande — il n'existe pas
de `/cg group flag`. Ouvre CheeseDash, va dans la section CheeseGuard, ouvre **Public
Areas** et mets `block-break` et `block-place` en refus là. Les deux zones suivent.

Contrôle ton travail avec `/cg info market_square` : la ligne `Groupe :` affiche désormais
`Public Areas`.

Le modèle complet d'héritage et de priorité, y compris ce qui se passe quand deux zones se
recouvrent, est dans [les groupes](groups.fr.md).

## 8. Sauvegarder les blocs de la zone

Un état de zone est un instantané de chaque bloc à l'intérieur de la boîte. C'est ainsi que
fonctionnent les remises à zéro d'arène.

```mcfunction
/cg state capture market_square Pristine
```

```text
État 'Pristine' capturé (18432 blocs, 3 entités)
```

⚠️ La capture parcourt chaque bloc sur le thread serveur. Une boîte de 20×10×20 fait 4 000
lectures, c'est instantané ; une boîte de 100×100×100 en fait un million et provoquera un
saccade visible. Garde des captures petites, ou fais-les sans personne connecté.

Liste ce que tu as :

```mcfunction
/cg state list market_square
```

```text
États de la zone 'market_square' :
  a1b2c3d4 — Pristine (18432 blocs, 3 entités)
```

La première colonne est l'identifiant d'état. Casse maintenant quelques blocs dans la zone,
puis remets-les :

```mcfunction
/cg state apply market_square a1b2c3d4
```

```text
État 'a1b2c3d4' appliqué à la zone 'market_square'
```

Les blocs cassés sont revenus. Essaie avec l'animation :

```mcfunction
/cg state apply market_square a1b2c3d4 true cascade_from_bottom 4 sparkle
```

```text
Animation de l'état 'a1b2c3d4' sur la zone 'market_square'...
```

Le retour signifie « démarré ». La reconstruction s'exécute sur les ticks suivants. Les
quatorze directions et les dix-huit particules sont listées dans
[les commandes](commands.fr.md#cg-state-apply) — et note qu'une faute de frappe dans l'une
ou l'autre retombe silencieusement sur le défaut plutôt que d'échouer.

## 9. Monter la prison

La prison est un arbre de commandes séparé, avec sa propre zone. Fabrique une zone
quelque part d'où l'on ne s'échappe pas — une pièce scellée suffit — et appelle-la
`prison`. Puis :

```mcfunction
/prison setzone prison
```

```text
Zone de prison définie : prison
```

Place-toi là où les prisonniers libérés doivent réapparaître, et enregistre l'endroit :

```mcfunction
/prison setout
```

```text
Point de sortie de prison défini : 118.5, 64.0, -302.5 (minecraft:overworld).
```

Enferme maintenant quelqu'un :

```mcfunction
/prison set Steve 10m test de la prison
```

```text
Steve a été envoyé en prison pour 10m.
```

Steve voit :

```text
⛓ Vous êtes en prison pour 10m. Raison : test de la prison
```

Il n'est **pas** téléporté par la commande. Le confinement par tick de `PrisonListener` le
ramène un instant plus tard, et à partir de là il ne peut plus quitter la zone et la
plupart des commandes lui sont refusées. Les durées se composent : `90s`, `30m`, `2h`,
`7d`, `1h30m` fonctionnent tous, et un nombre nu signifie des minutes.

Vérifie, puis libère :

```mcfunction
/prison list
```

```text
Prisonniers actifs (1)
• Steve — 9m47s — test de la prison
```

```mcfunction
/prison release Steve
```

```text
Steve a été libéré de prison.
```

Le mécanisme de confinement, la liste blanche des commandes autorisées et le sosie qui
remplace un prisonnier déconnecté sont dans [la prison](prison.fr.md).

## 10. Ouvrir le dashboard

Tout ce qui suit est plus simple dans CheeseDash, et une partie n'est possible que là : les
flags de groupe, la création de dimension, l'éditeur d'événements et sa timeline, les sets
de chunks, les circuits, les webhooks Discord et la configuration de vente d'une zone n'ont
aucune commande.

Ouvre `http://<serveur>:44311`, connecte-toi, et choisis **CheeseGuard** dans la barre
latérale. Les zones créées dans cette marche à suivre y sont listées, avec leurs flags sous
forme de grille plutôt qu'une commande par flag.

⚠️ Une chose à savoir avant de créer des comptes dashboard pour d'autres personnes : six
des huit API de CheeseGuard ne vérifient jamais le rôle du compte, donc un compte READ_ONLY
peut supprimer une zone ou effacer une dimension à travers ce mod. Traite tout compte
capable d'atteindre la section CheeseGuard comme un opérateur jusqu'à ce que ce soit
corrigé. Le détail est dans [l'architecture](architecture.fr.md#faiblesses-connues).

## Pour aller plus loin

| Tu veux | Lis |
|---|---|
| comprendre l'héritage des flags, la priorité et les zones qui se recouvrent | [zones et flags](zones-and-flags.fr.md) |
| organiser un événement chronométré avec des scores | [les événements](events.fr.md), puis [la timeline](timeline.fr.md) |
| donner à chaque joueur sa propre copie d'un monde | [les dimensions](dimensions.fr.md) |
| régénérer une partie de la carte selon un planning | [les chunks](chunks.fr.md) |
| piloter tout cela depuis un script | [le dashboard](dashboard.fr.md) |
| récupérer l'inventaire d'un joueur après un événement raté | [`/cg restoreinv`](commands-operations.fr.md#cg-restoreinv) |

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
