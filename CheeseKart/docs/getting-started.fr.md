[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, tu auras mis un joueur en kart, construit un preset à toi, et
réglé ce preset pendant que ce joueur roulait encore — c'est le flux de travail autour
duquel le mod est conçu.

Il te faut un serveur en marche avec CheeseKart et CheeseDash installés
([installation](installation.fr.md)), les droits d'opérateur au niveau de permission 2,
et un second joueur pour conduire. Tu peux utiliser ton propre double compte ; ce que tu
ne peux pas vraiment faire, c'est conduire et regarder les chiffres en même temps.

---

## 1. Fais l'inventaire de ce que tu as

```mcfunction
/cheesekart presets
```

```text
--- Presets (3) ---
  rally — Rally | slip=0.98 step=1.25 fwd=0.04 [locked]
  drift — Drift | slip=1.1 step=1.25 fwd=0.06 [locked]
  parkour — Parkour | slip=0.98 step=2.0 fwd=0.04 [locked]
```

Trois presets, semés au premier démarrage. Si tu obtiens `No presets configured`, quelque
chose a vidé `config/cheesekart.json` après le démarrage — voir
[dépannage](troubleshooting.fr.md#cheesekart-presets-répond-no-presets-configured).

## 2. Mets quelqu'un en kart

Place ton pilote là où il a de la place : une ligne droite plate de vingt blocs suffit
largement.

```mcfunction
/cheesekart start Steve rally
```

```text
Kart started for Steve (preset=rally)
```

**Le joueur d'abord, le preset ensuite.** Dans l'autre sens, tu obtiens
`Player not found: rally`.

Du côté de Steve : la caméra descend légèrement en position assise, rien n'est visible
sous lui, et maintenir la touche avant l'accélère progressivement au lieu de le faire
marcher. Appuyer sur Shift ne produit strictement rien — `rally` est `locked`.

Si Steve se retrouve plutôt assis dans un bateau visible qui se conduit comme un bateau,
il n'a pas OpenBoatUtils. Le kart fonctionne quand même, mais ce n'est pas un kart —
[pourquoi](physics.fr.md#pourquoi-il-doit-être-côté-client).

## 3. Observe la session

```mcfunction
/cheesekart list
```

```text
--- Active karts (1) ---
  Steve | preset=rally | 23s [locked]
```

Pas de `species=`, puisque `rally` n'en définit aucune et que tu n'en as pas passé.

## 4. Arrête tout

```mcfunction
/cheesekart stop Steve
```

```text
Kart stopped for Steve
```

Steve descend, le bateau disparaît, sa physique revient à celle du jeu de base, et il se
tient là où était le kart. La petite téléportation que tu remarqueras peut-être est
volontaire
([pourquoi](physics.fr.md#arrêter-un-kart-est-un-démontage-ordonné)).

---

## 5. Fabrique ton propre preset

Les presets fournis sont un point de départ, pas un design. Construis-en un pour une
piste réelle.

Depuis le dashboard : ouvre le module 🏎 **CheeseKart**, crée un preset, remplis les
champs. Ou en une seule requête — `$TOKEN` est un jeton de session obtenu auprès de
`POST /api/v1/auth/login` :

```bash
curl -X POST http://localhost:44311/api/v1/kart/presets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "id": "ice-sprint",
        "name": "Ice Sprint",
        "slipperiness": 1.12,
        "forwardAccel": 0.07,
        "yawAccel": 2.2,
        "turnForwardAccel": 0.008,
        "locked": true
      }'
```

Chaque champ que tu omets prend sa valeur par défaut : ce preset est donc complet et
utilisable — glissant, rapide, à la direction vive, et cantonné au sol puisque
`jumpForce` vaut `0.0` par défaut.

L'identifiant doit se composer de minuscules, de chiffres, de tirets ou de traits de
soulignement — `Ice Sprint` comme identifiant est rejeté par un `400`. Réutiliser un
identifiant existant renvoie un `409` plutôt que de l'écraser en silence.

Vérifie qu'il est enregistré :

```mcfunction
/cheesekart presets
```

```text
  ice-sprint — Ice Sprint | slip=1.12 step=1.25 fwd=0.07 [locked]
```

Aucun rechargement, aucun redémarrage. Le fichier de configuration sur le disque a déjà
été réécrit.

## 6. Règle-le pendant qu'on le conduit

C'est la partie qui vaut d'être apprise. Mets Steve dans ton nouveau preset :

```mcfunction
/cheesekart start Steve ice-sprint
```

Fais-lui faire un tour et demande-lui ce qui ne va pas. Admettons qu'il trouve le kart
trop flottant en virage. Sans l'arrêter :

```bash
curl -X PUT http://localhost:44311/api/v1/kart/presets/ice-sprint \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"slipperiness": 1.05}'
```

Le serveur écrit :

```text
[CheeseKart] Re-applied preset 'ice-sprint' to 1 active session(s)
```

Le kart de Steve change sous lui en l'espace d'un tick. Il n'en est jamais sorti, et
personne n'a rien redémarré. Itère : conduis, change un chiffre, conduis à nouveau. Ne
toucher qu'une valeur à la fois est le seul moyen de savoir ce qu'une valeur fait
vraiment.

Un `PUT` est une **fusion** : les champs que tu envoies changent, les autres conservent
leur valeur. La requête ci-dessus n'a donc touché qu'à l'adhérence.

## 7. Une grille entière d'un coup

Pour une vraie course, lancer les pilotes un par un ne tient pas : le premier parti
prend une avance qui se compte en secondes.

Utilise un événement chronométré CheeseGuard et donne-lui un effet `KART_START_ALL`
dont le `kartPresetId` pointe sur ton preset. Chaque participant est mis en kart au même
tick. `KART_STOP_ALL` y met fin ensemble, de la même manière. Voir
[l'API dashboard](dashboard.fr.md#appelé-depuis-les-événements-cheeseguard).

---

## Pour aller plus loin

- Chaque argument et chaque message d'échec → [commandes](commands.fr.md)
- Les seize champs d'un preset, avec leurs plages et leurs interactions → [configuration](configuration.fr.md)
- Ce qu'est réellement un kart, et le protocole de paquets OBU → [physique et sessions](physics.fr.md)
- Transformer les pilotes en Pokémon → [transformations CobbleShift](physics.fr.md#transformations-cobbleshift)

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
