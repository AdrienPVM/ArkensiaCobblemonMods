[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](audit.md)

# Journal d'audit

`AuditLogger.java`, 179 lignes, enregistre qui a fait quoi, quand, et depuis quelle IP.
Il existe parce qu'un dashboard dont le rôle `SUPER_ADMIN` peut lancer des commandes
serveur arbitraires, sur un serveur à plusieurs admins, a besoin de pouvoir répondre à
« qui a supprimé cette zone ». Le journal est volontairement lisible par tous les rôles,
`READ_ONLY` compris : une piste d'audit que seule la personne auditée peut lire n'est pas
une piste d'audit.

---

## Une entrée

```json
{
  "timestamp": "2026-03-09T18:42:11.004Z",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "action": "server.command",
  "detail": "say hello",
  "ip": "127.0.0.1"
}
```

| Champ | Notes |
|---|---|
| `timestamp` | `Instant.now().toString()` — ISO-8601, UTC, précision à la milliseconde |
| `username` | le nom d'utilisateur de la session, ou `unknown` pour un login échoué |
| `role` | le rôle **capturé dans la session**, pas le rôle actuel du compte. Rétrograde quelqu'un et sa session en cours continue de journaliser l'ancien rôle |
| `action` | une catégorie pointée, listée ci-dessous |
| `detail` | texte libre lisible, construit au point d'appel |
| `ip` | l'IP de la session, résolue une fois au login par `WebDashboard.getClientIp` |

Il n'y a ni identifiant d'entrée ni numéro de séquence. Les entrées sont ordonnées par
leur position dans le fichier ; deux actions dans la même milliseconde ne se distinguent
que par cet ordre.

---

## Ce qui est enregistré

Chacune de ces entrées vient d'un appel dans `DashboardApi`.

| Action | `detail` | Déclenché par |
|---|---|---|
| `auth.login` | `Logged in as admin (SUPER_ADMIN)` | `POST /auth/login`, en cas de succès |
| `auth.login_failed` | `Failed login attempt` | `POST /auth/login`, sur un token inconnu |
| `auth.logout` | `Logged out` | `POST /auth/logout` |
| `admin.account_create` | `Created account 'moderator' with role OPERATOR` | `POST /admin/accounts` |
| `admin.account_update` | `Changed role of 'moderator' to READ_ONLY` | `PUT /admin/accounts/{username}` |
| `admin.account_regen_token` | `Regenerated token for 'moderator'` | `POST /admin/accounts/{username}/regenerate` |
| `admin.account_delete` | `Deleted account 'moderator'` | `DELETE /admin/accounts/{username}` |
| `admin.token_regenerate` | `Master token regenerated` | `POST /dashboard/token/regenerate` |
| `server.command` | la commande complète | `POST /server/command` |
| `schedule.create` | `RESTART in 600s: Weekly maintenance` | `POST /server/schedule` |
| `schedule.cancel` | `Cancelled active schedule` | `DELETE /server/schedule/active` |
| `schedule.recurring_add` | `RESTART at 05:00` | `POST /server/schedule/recurring` |
| `schedule.recurring_update` | `Updated rec_9b21ef` | `PUT /server/schedule/recurring/{id}` |
| `schedule.recurring_delete` | `Deleted rec_9b21ef` | `DELETE /server/schedule/recurring/{id}` |
| `webhook.create` | `Created webhook 'Staff alerts'` | `POST /webhooks` |
| `webhook.update` | `Updated webhook wh_3f8a1c` | `PUT /webhooks/{id}` |
| `webhook.delete` | `Deleted webhook wh_3f8a1c` | `DELETE /webhooks/{id}` |
| `<prefix>.<méthode>` | `POST /api/v1/kart/start` | **chaque** requête non-`GET` vers une route de module |

C'est cette dernière ligne qui fait le volume. Le dispatcher audite automatiquement toute
requête de module qui n'est pas un `GET`, avec la méthode HTTP en minuscules comme suffixe
d'action, et la méthode plus le chemin complet comme détail. Modifier une zone CheeseGuard
produit donc `cheeseguard.put` avec `PUT /api/v1/cheeseguard/zones/spawn`, et l'auteur
d'un module obtient une piste d'audit de base gratuitement. Un module qui veut quelque
chose de plus parlant écrit sa propre entrée **en plus** : l'entrée automatique n'est pas
supprimée, tu verras donc les deux.

Note l'ordre pour `server.command` : l'entrée d'audit est écrite **avant** l'exécution de
la commande. Une commande qui lève une exception est quand même dans le journal. C'est le
bon sens pour une piste d'audit, mais cela signifie qu'une entrée n'est pas la preuve
qu'une commande a réussi.

### Ce qui n'est pas enregistré

Liste honnête, parce qu'un angle mort que tu ignores est pire qu'un angle mort :

- **Tous les `GET`.** Lire la liste des joueurs, exporter l'image d'un scoreboard, lister
  les comptes, et `GET /webhooks/{id}` — qui divulgue un secret Discord — ne laissent
  absolument aucune trace.
- **`DELETE /admin/sessions/{id}`.** Révoquer la session de quelqu'un n'est pas audité.
- **`PUT /dashboard/admin-player`** et les quatre routes `/dashboard/zone-folders`. Elles
  sont cosmétiques, mais ce sont des écritures qui ne laissent rien derrière elles.
- **`POST /webhooks/{id}/test`.** Création, modification et suppression sont auditées ; la
  publication de test, non.
- **Les actions en jeu.** Le menu rapide journalise dans le log du serveur
  (`[CheeseDash] QuickMenu: RoiCheese executing '/tp …'`) et pas dans le journal d'audit.
  Même chose pour les commandes console et pour celles tapées en chat par un opérateur.
  Le journal d'audit couvre le dashboard, pas le serveur.
- **L'ancienne valeur de quoi que ce soit.** `admin.account_update` enregistre le nouveau
  rôle, jamais le précédent. Pour une écriture de module, le détail est le chemin — pas le
  corps — donc tu sais qu'une zone a été modifiée et jamais ce qui a changé. Reconstruire
  un avant-après depuis ce journal n'est pas prévu.

---

## Stockage

Un fichier : `config/cheesedash_audit.json`, un tableau JSON plat d'objets d'entrée, mis
en forme, le plus ancien d'abord. Il est créé à la première action auditée, ce qui, sur
une installation neuve, est ta première connexion.

Tout le journal est tenu en mémoire dans une `Collections.synchronizedList`, plafonnée à
**10 000 entrées**. Au-delà, `log()` retire la plus ancienne pour chaque nouvelle entrée :
le plafond est donc une fenêtre glissante, et le fichier est réécrit à la taille tronquée.
À environ 200 octets par entrée, le fichier se stabilise autour de 2 Mo.

**Il n'y a pas de rotation.** Les entrées au-delà de 10 000 sont jetées, pas archivées. Sur
un serveur actif la fenêtre est plus courte qu'elle n'en a l'air : chaque écriture de
module produit une entrée, donc un après-midi d'édition de zones peut en représenter
plusieurs milliers. Si tu as besoin d'un historique plus long que la fenêtre, copie le
fichier ailleurs par une tâche planifiée — il est réécrit en entier à chaque entrée, donc
une copie est un snapshot cohérent de cet instant.

Le chemin d'écriture mérite d'être connu, car il est inhabituel :

```java
private void saveToDiskAsync() {
    Thread writer = new Thread(() -> { … Files.writeString(auditFile, GSON.toJson(arr)); },
        "CheeseDash-AuditWrite");
    writer.setDaemon(true);
    writer.start();
}
```

**Chaque entrée crée un thread et réécrit le fichier entier.** Deux conséquences.
D'abord le coût : dix actions en une seconde, c'est dix threads qui sérialisent chacun
jusqu'à 10 000 entrées. C'est invisible à vitesse humaine, et mesurable sous un script
qui martèle une route de module. Ensuite, plus sérieux : les écritures ne sont pas
sérialisées entre elles. Le snapshot de la liste est pris sous le verrou, mais deux
écrivains peuvent atteindre `Files.writeString` dans n'importe quel ordre, donc une
écriture lente démarrée plus tôt peut atterrir après une plus rapide et laisser le fichier
en retard d'une entrée. L'entrée suivante corrige la situation. Un `writeString`
interrompu par une JVM tuée peut aussi laisser un fichier tronqué, puisqu'il n'y a pas
d'écriture dans un temporaire suivie d'un renommage.

Ce dernier cas se dégrade proprement. Au démarrage, `loadFromDisk` parse le fichier et,
en cas d'échec, journalise
`[CheeseDash] Failed to load audit log, starting fresh: …` puis continue avec une liste
vide — **le fichier corrompu est alors écrasé à la prochaine action auditée, et
l'historique est perdu.** Si un journal compte pour toi et que le serveur refuse de le
charger, copie le fichier ailleurs avant de laisser le serveur réécrire. Un chargement
réussi journalise `[CheeseDash] Loaded 412 audit entries`, et un fichier contenant plus de
10 000 entrées est tronqué au chargement.

---

## Le relire

### Depuis le dashboard

La vue *Administration → Audit* dans la barre latérale, que tous les rôles peuvent
ouvrir. Elle pagine sur la route REST ci-dessous.

### Depuis l'API

```bash
curl -s "http://localhost:44311/api/v1/admin/audit?n=50" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "entries": [ { "timestamp": "…", "username": "admin", "…": "…" } ],
  "total": 412,
  "offset": 0,
  "limit": 100
}
```

Le plus récent d'abord. `n` vaut 100 par défaut et est borné à 1–500 ; `offset` compte à
rebours depuis l'entrée la plus récente, donc `offset=100&n=100` est la deuxième page.
`total` est le nombre d'entrées actuellement stockées, c'est-à-dire aussi le nombre
d'entrées que contient la fenêtre — pas le nombre d'actions jamais effectuées.

Pour tout parcourir, avance `offset` par pas égaux à ton `n` jusqu'à ce que le tableau
`entries` revienne incomplet. Les deux paramètres sont bornés plutôt que rejetés : une
valeur absurde te donne une page valide au lieu d'un `400`.

### Sur le WebSocket

Abonne-toi au canal `audit` et tu reçois un message par entrée, en direct, au moment de
l'enregistrement — poussé avant l'écriture disque. Le payload porte tous les champs
**sauf `ip`** :

```json
{
  "type": "audit",
  "timestamp": "2026-03-09T18:42:11.004Z",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "action": "server.command",
  "detail": "say hello"
}
```

L'omission a l'air volontaire — le flux en direct sert à surveiller l'activité, le journal
stocké à l'instruire — mais rien dans le code ne le dit. Note aussi que le WebSocket
n'accepte que le master token : le flux en direct est donc en pratique réservé au
`SUPER_ADMIN`, alors que la route REST est ouverte à tous. Détails dans
[WebSocket](websocket.fr.md#seul-le-master-token-est-accepté).

### Depuis le fichier

`jq` est l'outil le plus rapide pour une question que l'API ne sait pas exprimer. Toutes
les commandes lancées par un admin donné, par exemple :

```bash
jq -r '.[] | select(.action == "server.command" and .username == "admin")
       | "\(.timestamp)  \(.detail)"' config/cheesedash_audit.json
```

Ou tous les logins échoués, avec leur adresse source :

```bash
jq -r '.[] | select(.action == "auth.login_failed") | "\(.timestamp)  \(.ip)"' \
  config/cheesedash_audit.json
```

Une série de `auth.login_failed` depuis une même IP, c'est à quoi ressemble une tentative
de force brute vue d'ici. Le limiteur de débit l'arrête à 20 échecs par minute et par IP
en répondant `429`, et ces rejets ne sont **pas** audités : seules les tentatives allées
jusqu'à une comparaison de token le sont. Une attaque soutenue se lit donc comme une
rafale de vingt, puis un trou, encore et encore. Croise avec le log du serveur pour les
lignes de limitation de débit.

---

## Écrire des entrées depuis un module

```java
AuditLogger audit = CheeseDashMod.INSTANCE.getAuditLogger();
SessionManager.Session session = WebDashboard.getSession(exchange);
if (audit != null && session != null) {
    audit.log(session, "kart.preset_delete", "Deleted preset '" + id + "'");
}
```

La surcharge à deux arguments prend le nom d'utilisateur, le rôle et l'IP dans la
session : c'est ce que tu veux sur un thread HTTP. La forme à cinq arguments —
`log(username, role, action, detail, ip)` — est pour le cas sans session, comme la route
de login l'utilise pour une tentative échouée.

Les deux contrôles de nullité comptent. `getAuditLogger()` vaut null avant la fin de
`onInitialize`, et `getSession` renvoie null sur un contexte public non authentifié. Suis
la convention du cœur : `<prefix>.<verbe>` pour l'action, et un détail qui nomme l'objet
par son identifiant, pour que le journal reste parcourable au grep.

---

← [Webhooks Discord](discord-webhooks.fr.md) · [Menu rapide](quick-menu.fr.md) →
