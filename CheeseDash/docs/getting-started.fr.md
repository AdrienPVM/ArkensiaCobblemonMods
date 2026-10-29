[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, tu auras un dashboard qui tourne sur un serveur neuf, une
connexion en tant qu'`admin`, un second compte au rôle plus faible pour quelqu'un
d'autre, et une commande Minecraft exécutée depuis le navigateur puis retrouvée dans le
journal d'audit. Vingt minutes, en supposant que le serveur démarre.

Si tu veux seulement regarder l'interface, saute à
[l'étape 0](#étape-0-optionnelle--la-regarder-dabord) et arrête-toi là : elle n'a besoin
d'aucun serveur.

---

## Étape 0, optionnelle : la regarder d'abord

Ouvre `CheeseDash/preview.html` dans un navigateur, directement depuis le disque. Sans
build, sans serveur (le navigateur a quand même besoin d'internet pour les CDN de
Tailwind et d'Alpine). Il s'ouvre directement sur le dashboard : son `init()` pose
`auth = true`, donc le formulaire de connexion — qui accepterait `demo` ou n'importe quel
token de plus de quatre caractères — est sauté.

N'en attends pas trop. `preview.html` est une maquette autonome ancienne, en français, et
non la vraie interface : seule la vue d'ensemble a des données factices (TPS, nombre de
joueurs, compteurs, une liste d'activité récente), et les sept autres entrées de la barre
latérale — Zones, Groupes, Chunks, Dimensions, Événements, Joueurs, Serveur — affichent
un écran provisoire `Phase 2 — implémentation en cours`. Rien de ce que tu cliques
n'atteint un serveur. Pour voir les vraies vues, il te faut les étapes 1 à 4.

---

## Étape 1 : amener le jar sur le serveur

Construis-le :

```bash
cd CheeseDash && ./gradlew build     # gradlew.bat sous Windows
```

Copie `build/libs/cheesedash-1.0.0+1.21.1.jar` dans le `mods/` de ton serveur, à côté de
Fabric API. Les prérequis complets et les échecs de build classiques sont dans
[installation](installation.fr.md).

## Étape 2 : démarrer le serveur et récupérer le token

Démarre le serveur et surveille le log. Au milieu du bruit de démarrage, un encadré
apparaît exactement une fois :

```text
[CheeseDash] ╔═══════════════════════════════════════════════════╗
[CheeseDash] ║       FIRST RUN — Dashboard token generated       ║
[CheeseDash] ║  Save this token, you'll need it to log in:       ║
[CheeseDash] ║     EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX     ║
[CheeseDash] ║  Port: 44311                                         ║
[CheeseDash] ╚═══════════════════════════════════════════════════╝
```

Les lignes du token et du port débordent réellement de l'encadré : le code les complète
à une largeur fixe qui ne correspond pas à la bordure. Copie la chaîne du token. C'est le
**master token**, et il accorde `SUPER_ADMIN`, ce qui inclut
l'exécution de n'importe quelle commande serveur — traite-le exactement comme un mot de
passe root. Si tu rates l'encadré, la même valeur est dans `config/cheesedash.json`, clé
`token`.

Un peu plus tard, deux lignes confirment que les serveurs sont debout :

```text
[CheeseDash] ✅ Started on port 44311 — http://localhost:44311/
[CheeseDash] WebSocket started on port 44312
```

Si ce second groupe n'apparaît jamais, arrête-toi ici et lis
[dépannage](troubleshooting.fr.md) : rien de ce qui suit ne fonctionnera.

## Étape 3 : atteindre le dashboard

Sur la machine qui fait tourner le serveur :

```text
http://localhost:44311
```

Depuis ta propre machine, n'ouvre **pas** encore le port 44311 sur le pare-feu.
Tunnelise-le par SSH : une seule commande, et le chiffrement est offert.

```bash
ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@ton-serveur
```

Puis ouvre `http://localhost:44311` en local. Transfère aussi le 44312 : c'est le port du
WebSocket. Avec les pages fournies, cela ne change rien de visible aujourd'hui — leur
socket est refusée quoi que tu fasses ([pourquoi](websocket.fr.md#seul-le-master-token-est-accepté)),
et le TPS et le nombre de joueurs de l'en-tête viennent d'un sondage de
`GET /api/v1/status` toutes les deux secondes — mais tout client en direct en a besoin.
Pourquoi un tunnel plutôt qu'une simple ouverture des ports :
[exposition réseau](auth-and-roles.fr.md#exposition-réseau).

Tu devrais voir une carte de connexion sombre avec un seul champ de token, une case
« Mémoriser sur cet appareil » et un pied de page. L'interface est en français par
défaut : la langue vient du `localStorage` (`cg-lang`), vaut `fr` par défaut, et la carte
de connexion n'a pas de sélecteur de langue. Le champ s'intitule donc `TOKEN D'ACCÈS` et
le pied de page affiche `Port 44311 · TLS recommandé en production` (ce texte est fixe,
quel que soit ton port).

## Étape 4 : se connecter

Colle le master token et appuie sur **Accéder au dashboard**.

Derrière ce bouton, la page envoie :

```bash
curl -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"token":"EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX"}'
```

```json
{
  "sessionId": "ses_9mJ...",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "expiresIn": 86400000
}
```

À partir de là, chaque requête porte `Authorization: Bearer ses_9mJ...` au lieu du token
lui-même. La session dure 24 heures et le compteur repart à chaque requête : un
administrateur qui garde son onglet ouvert n'est jamais déconnecté.

Tu arrives sur la **Vue d'ensemble** : le TPS et le nombre de joueurs dans l'en-tête,
puis des cartes de statistiques, le MOTD et la version, les modules installés et quelques
actions rapides, à côté d'une barre latérale dont le contenu dépend entièrement des
modules installés. Avec CheeseDash seul, tu obtiens *Général* et *Administration* et rien
d'autre — c'est correct, pas un bug. Les cartes qui relèvent de modules affichent `—`.

Deux choses te sauteront aux yeux lors de cette première visite :

- Une demi-seconde après la connexion, une fenêtre te demande ton nom en jeu (*Joueur
  admin*), parce que `adminPlayerName` est encore vide. Tape ton nom Minecraft, ou
  ferme-la ; le nom est enregistré par `PUT /api/v1/dashboard/admin-player`.
- Le chiffre d'*uptime* est faux, typiquement des dizaines de milliers de jours. La vue
  d'ensemble lit `uptime` dans `GET /api/v1/status`, qui contient l'heure courante en
  epoch et non une durée ([détails](rest-api.fr.md#get-status)).

Si le token est refusé, voir
[dépannage](troubleshooting.fr.md#le-login-renvoie-401-invalid-token).

## Étape 5 : lancer une commande depuis le navigateur

Avec CheeseDash seul, il n'y a pas d'entrée **Serveur** dans la barre latérale : elle
appartient à la section CheeseGuard, qui n'apparaît que si CheeseGuard est installé. La
vue elle-même relève pourtant du cœur. Ouvre-la avec l'action rapide **Console serveur**
de la vue d'ensemble (ou va sur `#server`), trouve la boîte de commande, et lance quelque
chose d'inoffensif :

```mcfunction
say CheeseDash is up
```

Le message apparaît en jeu et dans la console du serveur. Deux choses se sont passées et
valent d'être comprises :

- Le handler HTTP n'a pas touché au jeu directement. Il a enveloppé l'appel dans
  `WebDashboard.executeOnServerThread(...)`, qui confie le travail au thread de tick du
  serveur et attend son résultat jusqu'à cinq secondes. Exécuter une commande depuis un
  thread HTTP serait une course contre toute la boucle de jeu.
- L'action a été auditée avant d'être exécutée, avec ton nom d'utilisateur, ton rôle, ton
  IP et la chaîne exacte de la commande.

Seul `SUPER_ADMIN` peut utiliser cette route. Un `OPERATOR` reçoit `403` — voir
[le tableau des rôles](auth-and-roles.fr.md#ce-que-peut-faire-chaque-rôle).

## Étape 6 : vérifier le journal d'audit

Toujours dans le dashboard, ouvre la vue d'audit, ou interroge l'API directement :

```bash
curl -H "Authorization: Bearer ses_9mJ..." \
  "http://localhost:44311/api/v1/admin/audit?n=5"
```

```json
{
  "entries": [
    {
      "timestamp": "2026-03-09T18:42:11.004Z",
      "username": "admin",
      "role": "SUPER_ADMIN",
      "action": "server.command",
      "detail": "say CheeseDash is up",
      "ip": "127.0.0.1"
    },
    {
      "timestamp": "2026-03-09T18:40:02.551Z",
      "username": "admin",
      "role": "SUPER_ADMIN",
      "action": "auth.login",
      "detail": "Logged in as admin (SUPER_ADMIN)",
      "ip": "127.0.0.1"
    }
  ],
  "total": 2,
  "offset": 0,
  "limit": 5
}
```

Du plus récent au plus ancien. Le fichier derrière tout ça est
`config/cheesedash_audit.json`, créé par ta connexion de l'étape 4. Détails dans
[journal d'audit](audit.fr.md).

## Étape 7 : donner un compte à quelqu'un d'autre

Ne distribue jamais le master token. Crée plutôt un compte avec le rôle le plus faible
qui fait le travail. Pour un modérateur qui doit modifier des zones et expulser des
joueurs mais pas lancer de commandes, c'est `OPERATOR` :

```bash
curl -X POST http://localhost:44311/api/v1/admin/accounts \
  -H "Authorization: Bearer ses_9mJ..." \
  -H "Content-Type: application/json" \
  -d '{"username":"moderator","role":"OPERATOR"}'
```

```json
{
  "username": "moderator",
  "role": "OPERATOR",
  "createdAt": 1772131331004,
  "token": "Jq7x...-WkR"
}
```

⚠️ **Ce champ `token` n'est renvoyé qu'une seule fois.** Tous les `GET` ultérieurs du
compte l'omettent. Copie-le maintenant et transmets-le à son propriétaire par un canal
privé. S'il est perdu, `POST /admin/accounts/moderator/regenerate` en émet un nouveau et
tue les sessions de l'ancien.

Les noms d'utilisateur sont validés : 1 à 32 caractères, `[a-zA-Z0-9_-]` uniquement, et
`admin` est réservé au master token. Le compte est écrit directement dans
`config/cheesedash.json` sous `accounts[]`, donc il survit à un redémarrage.

Ton modérateur se connecte comme à l'étape 4, avec son propre token. Il voit les mêmes
pages, moins la section *Administration*, que la barre latérale n'affiche qu'à
`SUPER_ADMIN` ; chaque requête mutante qui exige `SUPER_ADMIN` répondra `403` avec
`{"error":"Insufficient permissions — requires SUPER_ADMIN"}`.

## Étape 8 : planifier un redémarrage, puis l'annuler

Une dernière étape, pour voir le planificateur à l'œuvre :

```bash
curl -X POST http://localhost:44311/api/v1/server/schedule \
  -H "Authorization: Bearer ses_9mJ..." \
  -H "Content-Type: application/json" \
  -d '{"type":"RESTART","delaySeconds":600,"message":"Weekly maintenance"}'
```

Un compte à rebours démarre et les joueurs sont avertis en jeu à intervalles
décroissants. La vue **Serveur** affiche l'action en attente avec un décompte que le
navigateur calcule à partir de `scheduledAt`, lu dans `GET /api/v1/server/schedule`
(aucune poussée WebSocket n'atteint la page fournie). Annule-la avant son déclenchement :

```bash
curl -X DELETE http://localhost:44311/api/v1/server/schedule/active \
  -H "Authorization: Bearer ses_9mJ..."
```

```json
{"cancelled":true}
```

Le comportement complet, y compris les planifications récurrentes et ce que `RESTART`
fait réellement au processus, est dans [planificateur](scheduler.fr.md).

---

## Et ensuite

- Installer CheeseGuard, CheeseMarket ou n'importe quel autre mod-module ajoute des
  sections entières à la barre latérale sans aucune configuration du côté de CheeseDash.
  Chacun de ces mods documente ses propres pages.
- Écrire ton propre module est le sujet de [écrire un module](writing-a-module.fr.md),
  la page à lire si tu es ici en développeur plutôt qu'en administrateur.
- Avant d'exposer quoi que ce soit au-delà de `localhost`, lis
  [authentification et rôles](auth-and-roles.fr.md).

---

← [Installation](installation.fr.md) · [Configuration](configuration.fr.md) →
