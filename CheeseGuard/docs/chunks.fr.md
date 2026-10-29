[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](chunks.md)

# Chunks

Les ensembles de chunks répondent à un seul problème : une nature dévastée. Au bout de
quelques mois, la zone autour du spawn est rasée, les grottes sont vidées et il ne reste plus
un arbre. Le remède est de supprimer les fichiers de région de ce secteur pour que le monde se
régénère la prochaine fois que quelqu'un y passe. Les ensembles de chunks sont la comptabilité
autour de cette suppression — quel secteur, dans quelle dimension, selon quel horaire, avec
une sauvegarde vérifiée à chaque fois.

Deux fichiers d'état, tous deux sous `config/cheeseguard/` :

```text
config/cheeseguard/chunks.json             ← ensembles de chunks
config/cheeseguard/chunk_suggestions.json  ← emplacements proposés par les joueurs
config/cheeseguard/backups/                ← sauvegardes de régions avant suppression, 5 conservées
```

⚠️ Ce sous-système supprime des fichiers de région dans la sauvegarde du monde. Une
réinitialisation n'est pas une opération douce, et il n'y a pas d'annulation en dehors de la
sauvegarde automatique. Lis toute la page avant d'en lancer une sur un monde en production.

## Ensembles de chunks

Un `ChunkSet` est une liste nommée d'entrées de chunks, toutes dans une seule dimension, plus
un horaire de réinitialisation facultatif.

| Champ | Défaut | Signification |
|---|---|---|
| `name` | — | la clé ; sert aussi de préfixe au dossier de sauvegarde |
| `dimension` | `minecraft:overworld` | les fichiers de région de quel monde sont supprimés |
| `chunks` | vide | les entrées |
| `resetTrigger` | `MANUAL` | `MANUAL` ou `SCHEDULED` |
| `resetHour` / `resetMinute` | `6` / `0` | heure locale du serveur pour une réinitialisation planifiée |
| `resetDays` | vide | noms de jours en anglais majuscules ; vide signifie tous les jours |
| `lastAutoReset` | `0` | millisecondes epoch, pour empêcher un double déclenchement |

Une `ChunkEntry` est une coordonnée de chunk, un rayon et une étiquette :

| Champ | Signification |
|---|---|
| `x`, `z` | coordonnées de **chunk**, pas de bloc |
| `radius` | combien de chunks englober autour, dans toutes les directions |
| `label` | texte libre, affiché dans le dashboard |
| `centerX`, `centerZ` | coordonnées de bloc dérivées du centre du chunk, `x*16+8` |

Le rayon décrit un carré, pas un cercle : un rayon de 2 couvre un pavé de 5×5 chunks, soit 25.
`totalAffectedChunks` sur un ensemble est la somme des `(2r+1)²` de ses entrées, sans
déduplication — deux entrées qui se recouvrent sont comptées deux fois, le nombre est donc une
borne supérieure et non un décompte de chunks distincts.

Les entrées sont stockées en coordonnées de chunk, mais la suppression travaille sur des
**fichiers de région**, qui contiennent chacun 32×32 chunks. `ChunkEntry.getRegionFileName()`
associe une entrée à son `r.X.Z.mca`. C'est la chose la plus importante à comprendre ici :
demander la réinitialisation d'un seul chunk supprime tout le fichier de région de 512×512
blocs qui le contient. Il n'existe pas de suppression au chunk. Conçois tes ensembles à la
granularité de la région, ou tu effaceras bien plus que prévu.

L'égalité d'une entrée porte sur `(x, z, radius)` — l'étiquette n'en fait pas partie. C'est
pourquoi retirer une entrée exige les trois valeurs.

## Réinitialiser

`POST /chunks/sets/:name/reset` lance la suppression. `ChunkDeleter`
(`logic/ChunkDeleter.java`) fait le travail, et son contrat est volontairement strict :

1. Forcer la sauvegarde du monde de la dimension visée, pour que les fichiers sur disque
   soient à jour.
2. Développer l'ensemble en chunks concernés, puis en fichiers de région, puis filtrer sur
   ceux qui existent réellement.
3. **Créer une sauvegarde vérifiée.** Chaque fichier de région est copié dans
   `config/cheeseguard/backups/<ensemble>-<horodatage>/`, et la taille de la copie est
   comparée à celle de la source. Toute différence supprime le dossier partiel et lève une
   exception.
4. Seulement si la sauvegarde a abouti, supprimer les fichiers de région.
5. Écrire un `manifest.json` dans le dossier de sauvegarde, avec les métadonnées nécessaires à
   une restauration à la main.
6. Faire la rotation des sauvegardes, en conservant les 5 dossiers les plus récents.

Si la sauvegarde échoue, **rien n'est supprimé** :

```text
[CG] ABORTING chunk reset 'spawn_ring': backup failed — size mismatch for r.0.0.mca: source=4194304B, backup=2097152B
```

La réponse est le `DeletionResult` :

```json
{
  "setName": "spawn_ring",
  "status": "SUCCESS",
  "backupDir": "spawn_ring-20261029-040000",
  "totalChunks": 225,
  "totalRegions": 4,
  "deleted": 4,
  "notFound": 0,
  "failed": 0,
  "deletedFiles": ["r.0.0.mca", "r.0.-1.mca", "r.-1.0.mca", "r.-1.-1.mca"],
  "notFoundFiles": [],
  "failedFiles": [],
  "needsRestart": true
}
```

| `status` | Signification | HTTP |
|---|---|---|
| `SUCCESS` | tous les fichiers visés ont été sauvegardés et supprimés | `200` |
| `PARTIAL` | certains fichiers n'ont pu être supprimés ; la sauvegarde a réussi | `200` |
| `NO_REGIONS` | l'ensemble ne correspond à aucun fichier de région | `200` |
| `NO_FILES_FOUND` | les régions existent en théorie mais aucun `.mca` n'est sur le disque | `200` |
| `BACKUP_FAILED` | abandonné avant toute suppression | `500` |

Seul `BACKUP_FAILED` est traité comme un échec HTTP. Un `PARTIAL` revient en `200` : teste
`status` plutôt que le code de statut.

`needsRestart` vaut `true` dès que quelque chose a été supprimé. C'est un conseil, pas une
contrainte : le serveur continue de tourner, mais tout chunk encore chargé en mémoire peut
être réécrit sur le disque et recréer une partie de ce que tu viens de supprimer. Supprimer
des régions où personne ne se trouve, puis redémarrer au prochain moment opportun, est la
séquence fiable. La sauvegarde forcée de l'étape 1 réduit la fenêtre mais ne la ferme pas.

Un ensemble vide est refusé par `400 {"error":"Chunk set is empty"}`, ce qui évite l'accident
évident : réinitialiser un ensemble que tu n'as pas encore rempli.

## Planification

Avec `resetTrigger: "SCHEDULED"`, `CheeseGuardMod.tickChunkAutoReset()` confronte chaque
ensemble à l'horloge. Les conditions, dans l'ordre : l'ensemble est planifié, il n'est pas
vide, l'heure courante égale `resetHour`, la minute courante est à moins de 1 de
`resetMinute`, le jour courant figure dans `resetDays` (ou la liste est vide), et la dernière
réinitialisation automatique date de plus de 90 secondes.

Ce dernier filtre est ce qui empêche la tolérance de ±1 minute de déclencher la
réinitialisation deux ou trois fois. `lastAutoReset` est écrit et le stockage sauvegardé
*avant* que la suppression soit planifiée : un crash en pleine suppression ne provoque donc
pas une nouvelle exécution au tick suivant.

Le planificateur utilise `LocalDateTime.now()` — le fuseau horaire **local de la machine**, ni
UTC ni le temps Minecraft. Les noms de jours sont ceux de `java.time.DayOfWeek`, en anglais
majuscules : `MONDAY`, `TUESDAY`, `WEDNESDAY`, `THURSDAY`, `FRIDAY`, `SATURDAY`, `SUNDAY`.
L'API passe en majuscules ce que tu envoies, mais ne valide pas les noms : un jour mal
orthographié est stocké et ne correspond jamais, sans un mot.

Une réinitialisation automatique réussie s'annonce en chat à tous les joueurs connectés, en
texte traduit, et consigne :

```text
[CG] Auto-reset triggered for chunk set: spawn_ring
[CG] Auto-reset completed for 'spawn_ring' (dim: minecraft:overworld): 4 deleted, 0 failed, backup: spawn_ring-20261029-060000
```

`PUT /chunks/sets/:name/schedule` la configure. `trigger` doit valoir `MANUAL` ou
`SCHEDULED` ; toute autre valeur donne `400`. `hour` est borné à 0–23 et `minute` à 0–59 :
les valeurs hors plage sont donc corrigées sans un mot, pas refusées.

## Suggestions

Une suggestion, c'est un joueur qui dit « ce secteur est ravagé, pensez à le
réinitialiser ». Les joueurs en déposent une avec :

```text
/chunksuggest
```

La commande ne prend aucun argument et est **ouverte à tous les joueurs**, pas seulement aux
opérateurs. Elle est enregistrée comme commande autonome plutôt que sous `/cheeseguard`, et
c'est volontaire : le commentaire du code explique que le nœud racine du mod `chunkstoclear`
exige le niveau opérateur, et imbriquer celle-ci sous une racine gardée aurait emporté la
permission avec elle.

Ce que fait la commande :

1. Calculer les coordonnées de région du joueur, `floor(x) >> 9` et `floor(z) >> 9`.
2. Parcourir tous les ensembles de chunks qui visent la dimension **courante** du joueur, les
   développer en régions concernées, et vérifier si la région du joueur est déjà couverte. Si
   c'est le cas, le joueur apprend quel ensemble la couvre et rien n'est enregistré.
3. Sinon, enregistrer une `ChunkSuggestion` avec un identifiant aléatoire de 8 caractères, le
   nom et l'UUID du joueur, sa position exacte, son monde et un horodatage, puis confirmer
   avec les coordonnées arrondies.

Le contrôle de couverture est ce qui empêche la file de se remplir de doublons pour un secteur
déjà programmé. Il est par dimension : le même endroit dans le Nether et dans l'Overworld sont
indépendants.

Les suggestions se listent avec `GET /chunks/suggestions`. La position est tronquée en entiers
dans la réponse, même si elle est stockée en double.

### Accepter une suggestion

`POST /chunks/suggestions/:id/accept` avec `{"setName": "...", "radius": 1}` convertit une
suggestion en entrée sur un ensemble **existant** — elle ne crée pas l'ensemble. Un `setName`
absent ou vide donne `400`, un ensemble inconnu donne
`500 Chunk set not found: <nom>`.

Deux détails sont volontaires et méritent d'être conservés :

**Sécurité de dimension.** Si le monde de la suggestion ne correspond pas à la dimension de
l'ensemble, la requête est refusée par un `409` :

```json
{"error":"Dimension mismatch: suggestion is in 'minecraft:the_nether' but chunk set 'spawn_ring' targets 'minecraft:overworld'"}
```

Sans ce contrôle, accepter une suggestion du Nether dans un ensemble Overworld finirait par
supprimer des fichiers de région de l'Overworld à des coordonnées du Nether — le mauvais monde
entièrement. Un `409` plutôt qu'un `400` parce que la requête est bien formée ; c'est
l'appariement qui est en conflit.

**Conversion en coordonnées de chunk.** La position est convertie par
`(int) Math.floor(x) >> 4`, pas `(int) x >> 4`. Une simple conversion tronque vers zéro : un
joueur en `x = -0,5` se retrouverait dans le chunk 0 au lieu du chunk -1, et l'ensemble
viserait la région voisine. Passer par `Math.floor` d'abord est le correctif ; garde-le si tu
touches à ce code.

L'entrée créée porte l'étiquette `Suggestion by <playerName>`, les deux stockages sont
sauvegardés, et la suggestion est retirée dans la même tâche du thread serveur.

`DELETE /chunks/suggestions/:id` en écarte une sans y donner suite. Le joueur n'est notifié
dans aucun des deux cas.

## Autorisation

`ChunksApi` ne contient aucun appel à `WebDashboard.requireWrite` ni à `requireAdmin`. Les
routes qui modifient quelque chose sont ici des `POST`, `PUT` et `DELETE` : le répartiteur de
modules de CheeseDash les garde donc à `OPERATOR` ou au-dessus, et les deux routes `GET`
acceptent tous les rôles — voir
[API dashboard](dashboard.fr.md#ce-que-le-cœur-applique-réellement) pour ce que le cœur
vérifie exactement, et ce qu'il ne vérifie pas.

Il faut le dire franchement : `POST /chunks/sets/:name/reset` supprime des données du monde et
n'est protégé que par `OPERATOR`, pas par `SUPER_ADMIN`. Si ce n'est pas la frontière que tu
veux, c'est dans le module qu'il faudrait ajouter le contrôle.

Les détails des routes, les corps et les codes de statut sont dans
[API dashboard](dashboard.fr.md#chunks).

## Limites connues

- `totalAffectedChunks` compte deux fois les entrées qui se recouvrent.
- La granularité de suppression est le fichier de région (32×32 chunks), jamais le chunk.
- `PARTIAL` est renvoyé avec un HTTP `200`.
- Un chunk encore chargé en mémoire peut être réécrit après la suppression de son fichier de
  région ; redémarre après une réinitialisation.
- Les entrées de `resetDays` ne sont pas validées contre de vrais noms de jours.
- Le planificateur suit le fuseau horaire de la machine hôte.
- Les sauvegardes sont plafonnées à 5 dossiers et tournent silencieusement : un ensemble
  réinitialisé chaque semaine conserve environ cinq semaines d'historique, pas plus.

---

← [Joueurs](players.fr.md) · [Prison](prison.fr.md) →
