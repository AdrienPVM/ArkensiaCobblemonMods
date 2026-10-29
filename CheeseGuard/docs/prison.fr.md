[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](prison.md)

# Prison

La prison est un système de confinement à durée déterminée : un joueur est condamné pour une
durée, maintenu dans une zone, privé de presque toute interaction, et libéré automatiquement
quand le temps est écoulé. C'est un entre-deux entre l'avertissement et le bannissement — le
joueur reste sur le serveur et reste visible, et c'est tout l'intérêt.

Trois classes font le travail. `PrisonManager` (`logic/PrisonManager.java`) détient l'état et
la configuration. `PrisonListener` (`logic/PrisonListener.java`) applique le confinement et
pilote les transitions à la connexion et à l'expiration. `PrisonDoppelgangerManager` garde un
corps de remplacement dans la cellule pendant que le prisonnier est déconnecté.

L'état vit dans :

```text
config/cheeseguard/prison.json
```

Note le sous-dossier : ce fichier est sous `config/cheeseguard/`, contrairement aux fichiers
plus anciens du mod qui sont directement dans `config/` avec un préfixe `cheeseguard_`.

## Mise en place

La prison a besoin d'une zone avant de pouvoir enfermer qui que ce soit. N'importe quelle zone
convient — voir [Zones et flags](zones-and-flags.fr.md) — mais une zone réellement fermée est
une meilleure idée qu'une zone qui marque simplement un secteur, parce que le confinement est
appliqué en téléportant le joueur à l'intérieur, pas en bloquant son déplacement.

```text
/prison setzone <zoneId>
```

Puis, si tu veux, place-toi là où les libérés doivent apparaître et lance :

```text
/prison setout
```

Cela mémorise le monde, les coordonnées et l'orientation. `/prison clearout` l'effface, et la
libération retombe sur le point d'apparition de l'Overworld. Les deux sont aussi modifiables
depuis le dashboard, via `PUT /prison/config`.

Sans zone configurée, `POST /prison` répond :

```json
{"error":"Prison zone not configured"}
```

et la commande répond `Aucune zone de prison n'est configurée. Utilisez /prison setzone
<zoneId>.`

## Entrer

Deux portes mènent à l'intérieur.

**Par commande**, au niveau de permission 2 :

```text
/prison set <player> <duration> <reason>
```

**Depuis le dashboard**, `POST /api/v1/cheeseguard/prison` avec un corps
`{uuid|name, durationMs|duration, reason}`.

Dans les deux cas, le résultat est une `PrisonEntry` (`data/PrisonEntry.java`) stockée sous
l'UUID du joueur : UUID et nom, raison, `startedAt`, `durationMs`, `endsAt`, et qui a prononcé
la peine. Une peine prononcée depuis le dashboard enregistre `issuedBy` sous la forme
`dashboard:<username>` et `issuedByName` comme le nom d'utilisateur de la session : la piste
d'audit distingue donc les deux voies.

Emprisonner un joueur déjà emprisonné **remplace** la peine au lieu de la cumuler —
`active.put(uuid, e)` avec un `startedAt` recalculé. Pour allonger une peine existante, passe
par le chemin de prolongation (plus bas).

### Durées

`PrisonManager.parseDuration()` accepte un enchaînement de paires nombre-unité :

| Entrée | Résultat |
|---|---|
| `90s` | 90 secondes |
| `30m` | 30 minutes |
| `2h` | 2 heures |
| `7d` | 7 jours |
| `1d12h` | 36 heures |
| `45` | 45 **minutes** — un nombre nu est lu en minutes |

Tout le reste renvoie `-1`, que l'API traduit en
`{"error":"Duration is required and must be > 0"}` et la commande en
`Durée invalide : %s (utilisez par exemple 30m, 2h, 7d).`

L'analyseur n'a ni borne supérieure ni contrôle de débordement. `999999999d` produit un nombre
qui dépasse ce que les millisecondes peuvent porter, et un `endsAt` absurde. Reste dans des
durées raisonnables.

### Le joueur est déconnecté

`resolvePlayer()` essaie d'abord la liste des joueurs connectés, puis le cache utilisateur du
serveur. Un joueur déconnecté qui s'est déjà connecté une fois peut donc être condamné par nom
ou par UUID. Un nom que le serveur n'a jamais vu renvoie `404 Player not found`.

Une peine prononcée contre un joueur absent prend effet à sa prochaine connexion :
`ServerPlayConnectionEvents.JOIN` voit l'entrée active, le téléporte dans la cellule un tick
après la fin de la connexion, et envoie le message d'accueil. Si la peine a expiré pendant son
absence, le même gestionnaire le libère à la place et le téléporte au point de sortie.

Pendant que le prisonnier est déconnecté, `PrisonDoppelgangerManager` fait apparaître un
sosie dans la cellule avec son profil, pour que les autres joueurs voient qui purge une peine.
Il est retiré à la libération, y compris une libération déclenchée par l'expiration ou par un
administrateur alors que le joueur est toujours absent.

## À l'intérieur

Un joueur emprisonné n'a presque plus rien. `PrisonListener` enregistre cinq callbacks
Fabric, qui tous renvoient un échec pour un prisonnier et affichent
`⛓ Vous êtes en prison. Cette action est interdite.` au-dessus de la barre d'action :

| Callback | Bloque |
|---|---|
| `PlayerBlockBreakEvents.BEFORE` | casser des blocs |
| `UseBlockCallback` | le clic droit sur un bloc : ni coffres, ni portes, ni tables de craft |
| `UseItemCallback` | l'usage d'un item en main |
| `UseEntityCallback` | l'interaction avec une entité |
| `AttackEntityCallback` | frapper une entité |

Deux mixins couvrent ce que les callbacks ne peuvent pas :

- `ItemDropMixin` refuse qu'un prisonnier jette un item.
- `PrisonCommandMixin` annule toute commande dont le premier mot n'est pas dans une liste
  blanche d'exactement une entrée — `help`. Le message est
  `⛓ Vous êtes en prison. Cette commande est interdite.` **Les opérateurs ne sont pas
  exemptés**, et le commentaire du code le dit volontairement : un opérateur emprisonné est
  aussi confiné que n'importe qui. Il n'y a pas d'échappatoire `/prison release` depuis la
  cellule ; la libération doit venir d'un autre opérateur, de la console ou du dashboard.

Le confinement lui-même est un contrôle de présence, pas un mur. Toutes les 10 ticks (deux
fois par seconde), `onTick()` parcourt les joueurs connectés et, pour chaque prisonnier hors
de la zone de prison, le téléporte au centre de la zone et affiche
`⛓ Vous ne pouvez pas quitter la prison.` Le point d'atterrissage est choisi en balayant la
colonne centrale de la zone vers le bas jusqu'au bloc solide le plus haut surmonté de deux
blocs d'air, et `fallDistance` est remis à zéro ensuite.

Une demi-seconde suffit pour franchir quelques blocs : un prisonnier peut donc sortir
brièvement d'une petite cellule avant d'être ramené. Prévois de la marge en construisant la
cellule.

Si la zone configurée est supprimée pendant que des gens purgent une peine, le contrôle de
confinement se contente de rendre la main, et toutes les 30 secondes le log le signale :

```text
[CG/Prison] Configured prison zone 'jail' no longer exists; 2 prisoner(s) are unconstrained.
```

Les prisonniers conservent alors toutes leurs autres restrictions — ni blocs, ni items, ni
commandes — mais peuvent se promener partout. Ne supprime pas la zone de prison tant qu'elle
est occupée.

### Le message d'accueil

`welcomeMessage` est une chaîne libre stockée dans `prison.json`, envoyée ligne par ligne à la
connexion, avec deux marqueurs substitués :

| Marqueur | Remplacé par |
|---|---|
| `{reason}` | la raison de la peine, ou une chaîne vide |
| `{time}` | le temps restant, mis en forme comme `2d 3h 15m` |

Il est envoyé avec `Text.literal` : les codes couleur `§` fonctionnent et rien n'est traduit.
La valeur par défaut est en français et contient des codes `§c`, `§7` et `§f`. Change-la via
`PUT /prison/config` si ton serveur n'est pas francophone.

## Sortir

Trois chemins de sortie.

**L'expiration.** Une fois par seconde, `tickExpiry()` déplace vers l'historique chaque entrée
dont `endsAt` est passé, avec `releaseReason: "EXPIRED"`. Un joueur connecté est téléporté au
point de sortie et reçoit `✨ Votre peine est terminée. Vous êtes libre.`

**La grâce.** `/prison release <player>`, ou
`DELETE /api/v1/cheeseguard/prison/:uuid`. L'historique enregistre
`releaseReason: "PARDONED"` et l'auteur. Le joueur voit
`✨ Vous avez été gracié par un administrateur.`

**L'ajustement, qui peut raccourcir jusqu'à la libération.**
`PUT /api/v1/cheeseguard/prison/:uuid` déplace `endsAt`. Il prend le premier de ces champs
qu'il trouve :

| Champ du corps | Nouvelle fin |
|---|---|
| `endsAt` | cet horodatage absolu en millisecondes epoch |
| `durationMs` | `startedAt + durationMs` — recalculé depuis le début d'origine, pas depuis maintenant |
| `duration` | idem, analysé depuis une chaîne comme `2h` |
| `extendMs` | `endsAt + extendMs` — le seul champ qui s'ajoute à la fin courante |

Un `endsAt` dans le passé est refusé par `400 endsAt must be in the future` : tu ne peux donc
pas libérer quelqu'un par cette voie, utilise `DELETE`. Attention, `durationMs` et `duration`
recalculent depuis `startedAt` : envoyer `{"duration":"1h"}` à quelqu'un qui purge depuis deux
heures fixe une fin dans le passé et se fait refuser, ce qui surprend ceux qui attendaient
« une heure de plus ».

La libération passe toujours par `PrisonManager.release()`, qui retire l'entrée active, la
marque libérée, la place en tête de l'historique, tronque cette liste à 500 entrées,
sauvegarde, et retire le sosie.

## Inventaire

La prison ne touche **pas** aux inventaires. Pas de snapshot, pas de vidage, pas de
restauration : un prisonnier garde tout ce qu'il portait et ne peut simplement pas s'en servir.
C'est volontaire — les restrictions rendent l'inventaire inerte, et ne pas y toucher signifie
qu'il n'y a rien à rater.

Cela vaut la peine d'être dit clairement, parce que CheeseGuard *possède* bien un chemin de
snapshot et de restauration d'inventaire, et qu'il a dérapé une fois. Il appartient au
sous-système des **dimensions**, pas à la prison : `DimensionManager.exitClearInventory()`,
utilisé par les dimensions configurées en mode inventaire vidé. En juin 2026, ce chemin
dupliquait les objets. L'ancien code restaurait le snapshot d'entrée par
`player.readNbt(snapshot)` puis réinsérait par-dessus l'inventaire courant *en entier* — si
bien que chaque objet emporté à l'entrée revenait en double.

L'implémentation actuelle calcule à la place l'ensemble réellement gagné : snapshot de
l'inventaire courant, effacement, restauration du NBT d'entrée, puis soustraction des objets
détenus à l'entrée aux objets détenus à la sortie (appariement par item et composants,
décrémentation des quantités), et réinsertion du seul reliquat. Elle distingue aussi une
sortie fiable d'une sortie reprise après crash : si l'enregistrement en mémoire de l'inventaire
d'entrée a disparu, l'inventaire courant ne peut pas être cru et la sortie se limite à la
restauration, avec un avertissement :

```text
[CG] Clear inventory mode exited for Steve (crash-recovered snapshot) — restore-only, current inventory discarded to prevent duplication
```

Les détails complets sont dans [Dimensions](dimensions.fr.md). Si c'est mentionné ici, c'est
que « prison » et « dimension à inventaire vidé » sont faciles à confondre quand les deux
servent de zone de rétention, alors qu'un seul des deux déplace des objets. Si tu es tenté
d'ajouter un snapshot d'inventaire à la prison, lis d'abord cette fonction et garde la forme
soustraire-puis-restaurer : la version naïve, c'est le bug.

## Dashboard et WebSocket

`PrisonApi` est la seule API de CheeseGuard qui appelle `WebDashboard.requireWrite(exchange)`
correctement — `if (!WebDashboard.requireWrite(ex)) return;` sur chaque route qui modifie
quelque chose. Les lectures acceptent tous les rôles, `READ_ONLY` compris. Les routes et les
corps sont dans [API dashboard](dashboard.fr.md#prison).

Chaque changement d'état est diffusé sur le canal WebSocket `prison` par
`PrisonListener.broadcastPrisonEvent()` :

| `type` | Envoyé quand |
|---|---|
| `prison_added` | une peine commence |
| `prison_released` | expiration ou grâce |
| `prison_updated` | `endsAt` est ajusté depuis le dashboard |

Le payload porte l'entrée complète sous `entry`, plus `playerName`, `reason`, `durationMs`,
`duration`, `byName`, `releaseReason` et `releasedByName` aplatis au premier niveau. Cet
aplatissement existe pour que `DiscordWebhookManager` puisse récupérer les champs sans entrer
dans l'objet imbriqué.

La même fonction diffuse aussi une ligne de chat publique pour `prison_added` et
`prison_released` — traduite, donc chaque joueur la lit dans sa langue. `prison_updated` est
volontairement muet en chat : un ajustement de durée depuis le dashboard est de la comptabilité,
pas une nouvelle.

## Limites connues

- Le contrôle de confinement tourne à 2 Hz : de brèves échappées d'une petite cellule sont
  possibles.
- Supprimer la zone de prison laisse les prisonniers sans contrainte, avec pour seul signe une
  ligne de log toutes les 30 secondes.
- `parseDuration` n'a pas de borne supérieure ; une durée absurde produit un `endsAt` absurde.
- Un `PUT` avec `durationMs` ou `duration` recalcule depuis `startedAt`, ce qui se lit comme
  « prolonger » mais n'en est pas.
- Le `welcomeMessage` par défaut est en français, avec des codes couleur `§` en dur, et n'est
  pas traduit.
- L'historique est plafonné à 500 entrées, les plus anciennes disparaissent sans un mot.

---

← [Chunks](chunks.fr.md) · [Courses](races.fr.md) →
