[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](minigames.md)

# Mini-jeux

Le sous-système de mini-jeux n'a rien de commun avec les classements : aucune donnée de
monde, aucune image rendue, aucune entrée de configuration. C'est un petit registre de
résultats d'événements qu'un administrateur remplit à la main après avoir organisé une
chasse au trésor, une course, un tournoi — tout ce où des gens terminent dans un ordre.

Sa raison d'être est que les classements ne peuvent mesurer que ce que Cobblemon et le
vanilla comptent déjà. Un événement n'a pas de compteur, donc quelqu'un doit saisir le
résultat.

---

## Le modèle

```text
MiniGameManager                config/cobblecheesescore_minigames.json
 └── games : Map<gameId, MiniGame>       ordonnée par insertion
       ├── displayName : String
       └── sessions : List<GameSession>  chronologique, plafonnée à 500
             ├── date : long             epoch ms, posée à l'insertion
             └── rankings : List<SessionEntry>
                   └── uuid · name · score (double)

MiniGameLegends                config/cobblecheesescore_legends.json
 └── records : List<LegendRecord>        chronologique, jamais purgée
       gameId · gameName · date · winnerUuid · winnerName · winnerScore
       · firstEverForGame · podium (top 3)
```

Un jeu est un identifiant et un libellé. Une session est une exécution de ce jeu, avec les
joueurs qui y ont participé et leurs scores bruts. Rien ne relie une session à un événement
en jeu, à une heure ou à un lieu : la date est simplement le moment où le résultat a été
soumis.

`MiniGameManager` est construit pendant `onInitializeServer`, avant que le monde existe, et
charge son fichier sur place. Les mini-jeux fonctionnent donc sur un serveur dont le dossier
de monde n'a aucune donnée Cobblemon.

## Notation

Les scores bruts d'une session sont stockés mais jamais classés. Ce qui produit un
classement est une règle de points de podium appliquée à **l'ordre du tableau tel qu'il a
été soumis** :

| Position dans le tableau soumis | Points |
|---|---|
| 1er | 3 |
| 2e | 2 |
| 3e | 1 |
| 4e et au-delà | 0 |

Les points s'accumulent d'une session à l'autre. `formattedScore` vaut `"<n> pts"`, trié
par ordre décroissant.

Cela a deux conséquences qu'il vaut mieux anticiper.

**C'est l'émetteur qui décide de l'ordre.** `POST /minigames/:id/sessions` stocke le
tableau exactement tel qu'il a été envoyé, et `computePointsFromSessions` prend les indices
0, 1 et 2. Soumets une session triée par temps croissant dans une course où le plus rapide
gagne, et tu viens de donner trois points au joueur le plus lent. Rien ne t'avertira ; les
valeurs `score` brutes sont inertes.

**Tout le monde sous la troisième place est invisible.** Ces joueurs sont stockés dans la
session et apparaissent dans `GET /minigames/:id`, mais ils ne contribuent à aucun
classement : un joueur qui finit toujours quatrième n'apparaît donc jamais dans l'agrégat.
`playerCount` compte les joueurs montés au moins une fois sur un podium, pas les
participants.

Deux joueurs à égalité de points sont ordonnés selon ce que `Collections.sort` fait de clés
égales — un tri stable, donc par ordre de première apparition dans la map des points, qui
est l'ordre dans lequel les sessions ont été parcourues. Il n'y a aucun critère de
départage.

## Classements par jeu et global

`computeGameRanking(gameId)` applique la règle aux sessions d'un seul jeu.
`computeGlobalRanking()` concatène les sessions de tous les jeux et applique la même règle :
un jeu à 40 sessions domine donc un jeu à 3. La pondération n'est pas configurable.

Les deux sont calculés à la demande, sur le thread HTTP, chaque fois qu'une route le
demande. Rien n'est mis en cache. Avec 500 sessions par jeu et une poignée de jeux, cela
reste d'un coût dérisoire.

## Le plafond de 500 sessions et l'archive des légendes

`addSession` ajoute à la fin, puis retire des entrées en tête jusqu'à ce que le jeu ne
contienne au plus 500 sessions. Les sessions retirées ont disparu de
`cobblecheesescore_minigames.json` et disparu des classements : un classement agrégé est une
fenêtre glissante sur les 500 derniers résultats, pas un total à vie.

Avant de faire tourner la fenêtre, `addSession` transmet le résultat à
`MiniGameLegends.recordSession`, qui ajoute un enregistrement permanent à
`config/cobblecheesescore_legends.json` :

```json
{
  "records": [
    {
      "gameId": "chasse_au_tresor",
      "gameName": "Chasse au trésor",
      "date": 1761700000000,
      "winnerUuid": "5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e",
      "winnerName": "RoiCheese",
      "winnerScore": 420.0,
      "firstEverForGame": true,
      "podium": [
        { "rank": 1, "uuid": "5e1b2c3d-…", "name": "RoiCheese", "score": 420.0 },
        { "rank": 2, "uuid": "0b1c2d3e-…", "name": "Alex",      "score": 310.0 }
      ]
    }
  ]
}
```

Ce fichier est écrit via un fichier temporaire suivi d'un déplacement atomique : un crash
en cours d'écriture ne peut donc pas le tronquer — c'est le seul fichier du mod à prendre
cette précaution. Rien ne le purge, rien n'en supprime, et supprimer un jeu ou une session
n'y touche pas. C'est l'enregistrement permanent de qui a gagné quoi.

⚠️ **Aucune route et aucune commande ne lit le fichier des légendes.**
`MiniGameLegends.getAll()`, `getByGameId()` et `MiniGameManager.getLegends()` n'ont aucun
appelant. L'archive est en écriture seule vue de l'extérieur : pour la consulter, tu ouvres
le JSON. `firstEverForGame` et `isFirstEverForGame()` sont calculés et stockés pour un
consommateur qui n'existe pas encore.

Un tableau `rankings` vide est stocké comme session, mais ignoré par l'archive, qui sort
immédiatement sur une liste vide.

## En mener un de bout en bout

Il n'y a aucune commande pour tout cela ; la seule entrée est le dashboard ou l'API REST.
Forme complète des requêtes et des réponses dans
[dashboard](dashboard.fr.md#routes-mini-jeux).

1. Crée le jeu une fois, avec un identifiant que tu peux taper dans une URL — minuscules,
   sans espace, sans barre oblique. `POST /minigames` avec
   `{"id":"chasse_au_tresor","displayName":"Chasse au trésor"}`.
2. Organise l'événement en jeu. Note qui a terminé où, et l'UUID de chaque joueur. C'est
   l'UUID qui identifie un joueur d'une session à l'autre ; le nom n'est qu'un libellé, et un
   renommage fait gagner le nom soumis le plus récemment dans le classement, tandis que
   l'ancien reste dans les sessions stockées.
3. Soumets le résultat avec `POST /minigames/:id/sessions`, **le meilleur d'abord**.
4. Vérifie avec `GET /minigames/:id`. La nouvelle session est le premier élément de
   `sessions`, et l'agrégat `ranking` a bougé.
5. Corrige une erreur avec `DELETE /minigames/:id/sessions/:idx`, en utilisant l'`index`
   fourni par l'objet session, puis resoumets. Supprimer décale tous les indices supérieurs
   d'un cran vers le bas : récupère la liste à nouveau entre deux suppressions.

Une session soumise dans le mauvais ordre ne peut pas être réordonnée sur place. Supprime-la
et soumets-la de nouveau.

## Lacunes connues

- Rien n'enregistre *qui* a soumis une session. Le journal d'audit de CheeseDash note la
  requête, mais la session stockée ne porte aucun auteur.
- Rien ne valide qu'un `uuid` est un vrai joueur, ni même un UUID. Une faute de frappe crée
  un participant distinct avec ses propres points.
- Les identifiants de jeu ne sont pas validés. Un identifiant contenant `/` rend toutes les
  routes `/minigames/:id` inatteignables pour ce jeu ; un identifiant valant `global` est
  masqué par la route du classement global.
- `MiniGameManager.save()` réécrit tout le fichier à chaque mutation, sans atomicité. Un
  crash pendant l'écriture peut laisser un `cobblecheesescore_minigames.json` tronqué, sur
  lequel `load()` échouera, journalisera, et repartira de zéro. Le fichier des légendes est
  la copie de secours des données dans ce cas, mais la restauration est un travail manuel.

---

← [Export PNG](png-export.fr.md) · [API dashboard](dashboard.fr.md) →
