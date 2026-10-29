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
build, sans serveur. Tape `demo` dans le champ du token et appuie sur **Access
dashboard**.

Toutes les vues fonctionnent, appuyées sur des données factices : zones inventées,
joueurs inventés, commandes inventées. Rien de ce que tu cliques n'atteint un serveur, et
rien n'est sauvegardé. Ce fichier existe pour que tu puisses juger le mod avant de
l'installer, et pour que le travail sur le frontend n'exige pas un serveur Minecraft qui
tourne.

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
[CheeseDash] ║   EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX     ║
[CheeseDash] ║  Port: 44311                                      ║
[CheeseDash] ╚═══════════════════════════════════════════════════╝
```

Copie cette chaîne. C'est le **master token**, et il accorde `SUPER_ADMIN`, ce qui inclut
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
Tunnelise-le par SSH : une seule commande, et le TLS est offert.

```bash
ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@ton-serveur
```

Puis ouvre `http://localhost:44311` en local. Transfère aussi le 44312, sinon le
graphique de TPS en direct et la liste des joueurs ne se mettront jamais à jour : ces
données arrivent par le WebSocket, pas par HTTP. Pourquoi c'est préférable à une simple
ouverture des ports : [exposition réseau](auth-and-roles.fr.md#exposition-réseau).

Tu devrais voir une carte de connexion sombre avec un seul champ, `ACCESS TOKEN`, et un
pied de page qui affiche `Port 44311 · TLS recommended in production`.

## Étape 4 : se connecter

Colle le master token et appuie sur **Access dashboard**.

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

Tu arrives sur **Overview** : TPS, nombre de joueurs, usage du heap, uptime, et une barre
latérale dont le contenu dépend entièrement des modules installés. Avec CheeseDash seul,
tu obtiens *General* et *Administration* et rien d'autre — c'est correct, pas un bug.

Si le token est refusé, voir
[dépannage](troubleshooting.fr.md#le-login-renvoie-401-invalid-token).

## Étape 5 : lancer une commande depuis le navigateur

Va dans **Server** dans la barre latérale, trouve la boîte de commande, et lance quelque
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

Ton modérateur se connecte comme à l'étape 4, avec son propre token. Il verra les mêmes
pages ; chaque requête mutante qui exige `SUPER_ADMIN` répondra `403` avec
`{"error":"Insufficient permissions — requires SUPER_ADMIN"}`.

## Étape 8 : planifier un redémarrage, puis l'annuler

Une dernière étape, pour voir le planificateur et le WebSocket travailler ensemble :

```bash
curl -X POST http://localhost:44311/api/v1/server/schedule \
  -H "Authorization: Bearer ses_9mJ..." \
  -H "Content-Type: application/json" \
  -d '{"type":"RESTART","delaySeconds":600,"message":"Weekly maintenance"}'
```

Un compte à rebours démarre, les joueurs sont avertis en jeu à intervalles décroissants,
et le dashboard affiche l'action en attente en direct. Annule-la avant son déclenchement :

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
