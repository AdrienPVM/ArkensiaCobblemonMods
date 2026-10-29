[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](physics.md)

# Physique et sessions

Un kart n'est pas une entité ajoutée par ce mod. C'est un bateau vanilla, caché, sur
lequel un joueur est verrouillé, et dont le comportement est surchargé par des paquets
envoyés au client de ce joueur. Trois mécanismes distincts doivent s'aligner pour que la
sensation soit juste, et chacun échoue d'une manière différente quand ce n'est pas le
cas.

---

## De quoi un kart est fait

`/cheesekart start` fait six choses, dans cet ordre :

1. **Fait apparaître un bateau** à la position exacte du joueur, avec trois attributs
   vanilla posés : invisible, invulnérable, silencieux. L'attribut invisible ne cache pas
   réellement un bateau : le rendu vanilla des bateaux ne le consulte jamais. Ce qui cache
   le kart, c'est le [mixin de rendu](architecture.fr.md#les-deux-mixins-client), sur les
   clients qui ont CheeseKart.
2. **Force la montée** du joueur. « Forcer » désigne ici le chemin vanilla qui ignore
   la question de savoir si c'est normalement permis. Si cela échoue malgré tout, le
   bateau est jeté immédiatement pour ne pas laisser d'entité orpheline dans le monde, et
   la commande signale l'échec.
3. **Pousse la physique** — douze paquets OpenBoatUtils, un par réglage.
4. **Résout l'espèce**, l'argument explicite d'abord, puis le champ `species` du preset,
   puis rien.
5. **Transforme le joueur** via CobbleShift, si une espèce a été résolue et que
   CobbleShift est présent.
6. **Enregistre la session** et diffuse le nouvel état de kart à tous les clients.

Lancer un kart pour quelqu'un qui en a déjà un arrête d'abord l'ancienne session, ce qui
fait de `start` le moyen de changer le preset d'un pilote en pleine course.

---

## Le protocole OpenBoatUtils

OpenBoatUtils écoute sur le canal `openboatutils:settings`. Son payload est du binaire
brut, pas du JSON :

```text
[short packetId][value]
```

Les deux parties sont en big-endian, l'ordre par défaut de Netty et donc ce que
produisent `PacketByteBuf.writeShort` et ses voisines.

La largeur de la valeur dépend du réglage : un float pour la plupart, un double pour la
gravité, un octet unique pour un booléen, rien du tout pour une réinitialisation. Ces
identifiants de paquets **ne sont pas documentés en amont** — la liste ci-dessous a été
déduite de la lecture d'OpenBoatUtils.

| Id | Réglage | Payload | Champ du preset |
|---|---|---|---|
| 0 | retour à la physique vanilla | — | envoyé à l'arrêt |
| 1 | hauteur de marche | float | `stepHeight` |
| 2 | glissance | float | `slipperiness` |
| 4 | dégâts de chute | booléen | `fallDamage` |
| 6 | contrôle en vol | booléen | `airControl` |
| 7 | force de saut | float | `jumpForce` |
| 9 | gravité | **double** | `gravity` |
| 10 | accélération en lacet | float | `yawAccel` |
| 11 | accélération avant | float | `forwardAccel` |
| 12 | accélération arrière | float | `backwardAccel` |
| 13 | accélération avant en virage | float | `turnForwardAccel` |
| 14 | cumul de l'accélération | booléen | `accelStacking` |
| 28 | pas aérien | booléen | `airStepping` |

Les trous sont réels : les identifiants 3, 5, 8 et tout ce qui va de 15 à 27 sont des
réglages OBU que ce mod n'utilise pas.

### Pourquoi le payload est envoyé de deux manières différentes

OBU peut être installé ou non **sur le serveur**, et cela change la façon de construire
le paquet. Les deux cas sont traités à l'initialisation du mod :

- **OBU présent côté serveur.** Enregistrer un second type de payload sur un canal qui
  appartient déjà à OBU serait un conflit ; CheeseKart emprunte donc la classe de payload
  d'OBU elle-même — `dev.o7moon.openboatutils.OpenBoatUtils$BytePayload` — par réflexion,
  en mettant en cache son constructeur et son `ID` statique. Le log affiche
  `OBU detected — using OBU BytePayload via reflection`.
- **OBU absent côté serveur.** Personne ne possède le canal, donc CheeseKart y enregistre
  son propre payload de repli. Le log affiche
  `OBU not loaded — registered fallback payload channel`. Les octets sur le fil sont
  identiques dans les deux cas.

La réflexion est la partie fragile. Si OBU renomme ou restructure cette classe, la
recherche échoue au démarrage et l'erreur est écrite une fois — après quoi plus aucun
paquet de physique n'est envoyé et les karts se conduisent comme des bateaux. Le mod
continue de tourner.

### Pourquoi il doit être côté client

Le serveur ne simule jamais la physique d'un kart. Il envoie des nombres ; **c'est le
client du pilote** qui les applique. Avant d'envoyer quoi que ce soit, le mod vérifie que
ce client a bien enregistré le canal, et s'abstient complètement d'envoyer sinon. Un
pilote sans OBU installé obtient donc un bateau invisible fonctionnel au comportement
vanilla, sans erreur et sans pollution du log.

La conséquence pratique, c'est que chaque pilote a besoin d'OBU côté client, et qu'un
serveur qui ne l'inscrit pas dans sa liste de mods requis aura des pilotes
discrètement plus lents que les autres.

---

## Le mode verrouillé

`locked` décide si le pilote peut partir. Il vaut `true` par défaut, et il est imposé
deux fois.

**Côté client**, un mixin annule `dismountVehicle` tant que le joueur local est marqué
comme étant en kart. Appuyer sur Shift ne fait rien. C'est la partie que le pilote
ressent.

**Côté serveur**, à chaque tick, chaque session est vérifiée : si le joueur ne chevauche
plus rien, le mod cherche son bateau et le remet en selle. Si le bateau a disparu, la
session est arrêtée à la place. C'est ce qui fait tenir le mode verrouillé même pour un
client qui n'a pas le mod installé, ou pour un client qui a trouvé une autre sortie.

Avec `locked: false`, l'inverse s'applique : un joueur qui descend voit sa session
s'arrêter proprement au tick suivant, bateau supprimé et physique réinitialisée.

Cette même vérification par tick attrape un troisième cas — le joueur chevauche *quelque
chose*, mais son bateau est mort ou a été supprimé. Cette session est arrêtée plutôt que
laissée à pointer vers une entité morte.

---

## Arrêter un kart est un démontage ordonné

L'ordre dans `stopKart` n'est pas arbitraire, et deux de ses étapes existent à cause de
bugs qui ont été corrigés en les déplaçant.

1. **Annoncer d'abord que le kart est terminé.** Les clients effacent leur état de kart
   avant l'arrivée du paquet de descente. Dans l'autre sens, le mixin de descente est
   encore armé quand la descente atteint le client, et il annule la descente même que le
   serveur vient de demander.
2. **Faire descendre** le joueur.
3. **Réinitialiser la physique** — paquet d'identifiant 0. Après la descente, pour que le
   client réinitialise les réglages dont il ne se sert plus plutôt que ceux dont il se
   sert encore.
4. **Jeter le bateau**, recherché par identifiant d'entité d'abord et par UUID en repli,
   dans tous les mondes chargés.
5. **Annuler la transformation CobbleShift**, s'il y en avait une.
6. **Remettre la vélocité du joueur à zéro et le re-téléporter à sa propre position.**
   Sans cela, un joueur qui descend d'un bateau invisible qui tournait avec une gravité
   personnalisée peut rester bloqué ou dériver. Téléporter quelqu'un là où il se trouve
   déjà est un moyen économique de forcer une resynchronisation complète de position et
   de vélocité.

---

## Cycle de vie d'une session

Une session est un enregistrement immuable : l'UUID et le nom du joueur, l'identifiant de
preset, l'espèce résolue, l'identifiant d'entité et l'UUID du bateau, un horodatage de
départ, et le drapeau `locked` copié depuis le preset au moment du lancement. Modifier le
champ `locked` d'un preset ne change pas une session déjà en cours.

| Événement | Ce qui se passe |
|---|---|
| **Un administrateur lance un kart** | session créée, physique poussée, tous les clients avertis |
| **À chaque tick serveur** | les passagers manquants sont remis en selle ou arrêtés, les bateaux morts nettoyés, les joueurs disparus retirés |
| **Un joueur se connecte** | chaque kart actif est envoyé au nouveau client, pour qu'un arrivant tardif voie correctement les karts des autres pilotes |
| **Un joueur se déconnecte** | session retirée immédiatement ; le bateau est jeté depuis le thread serveur, parce que l'événement de déconnexion arrive sur un thread réseau |
| **Un preset est modifié via `PUT`** | la physique est repoussée vers chaque session qui utilise ce preset — [réglage à chaud](dashboard.fr.md#put-presetsid) |
| **Le serveur s'arrête** | chaque session est démontée, pour qu'aucun bateau invisible ne survive à un redémarrage |

Les sessions ne vivent qu'en mémoire. Elles ne sont pas écrites sur le disque et ne
survivent pas à un redémarrage — c'est intentionnel : un kart qui survivrait au serveur
serait un bateau invisible dont personne ne pourrait sortir.

---

## Transformations CobbleShift

Entièrement optionnelles, et atteintes uniquement par réflexion — CheeseKart compile et
tourne sans rien connaître de CobbleShift. Au démarrage du serveur, il tente de trouver
`com.cobbleshift.CobbleShiftMod`, de lire son `INSTANCE`, d'obtenir le gestionnaire de
transformations, et de mettre en cache les méthodes `transform` et `revert`.

La recherche est retentée plus tard si l'instance de CobbleShift n'est pas encore prête,
plutôt qu'abandonnée définitivement, parce que l'ordre d'initialisation des mods n'est pas
garanti. Trois issues se retrouvent dans le log :

```text
[CheeseKart] CobbleShift integration active
[CheeseKart] CobbleShift not installed — transform features disabled
[CheeseKart] Failed to hook CobbleShift: <reason>
```

La troisième est celle dont il faut se soucier : CobbleShift est installé, mais sa forme
a changé. Une transformation ratée ne fait jamais échouer le kart — le pilote obtient son
kart sans le costume.

---

← [Configuration](configuration.fr.md) · [API dashboard](dashboard.fr.md) →
