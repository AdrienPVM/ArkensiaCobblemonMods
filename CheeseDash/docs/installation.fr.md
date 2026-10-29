[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CheeseDash déclare `"environment": "*"`, donc Fabric le charge des deux côtés — mais les
deux côtés font des choses complètement différentes. La moitié serveur, c'est le
dashboard : le serveur HTTP, le serveur WebSocket, le registre de modules, le
planificateur, les webhooks. La moitié cliente, c'est une touche et un écran, rien
d'autre. **Installer le mod uniquement sur le serveur est une configuration tout à fait
normale.**

---

## Ce qu'il te faut

### Serveur

| | Version | Pourquoi |
|---|---|---|
| Minecraft | **1.21.1** exactement | déclaré en `~1.21.1` |
| Fabric Loader | ≥ 0.15.11 | déclaré dans `fabric.mod.json` |
| [Fabric API](https://modrinth.com/mod/fabric-api) | n'importe quel build 1.21.1 | événements de cycle de vie, événements de tick serveur, réseau |
| Java | **21 ou plus** | le mod compile avec `release = 21` et utilise `com.sun.net.httpserver` du JDK |

Rien d'autre. CheeseDash n'a ni `suggests` ni dépendance optionnelle : c'est lui dont les
autres mods dépendent, pas l'inverse.

### Client, optionnel

| | Requis ? | Sans lui |
|---|---|---|
| CheeseDash | non | rien ne casse. Le dashboard est une page web, n'importe quel navigateur l'atteint. Tu perds le menu rapide en jeu, qui est la seule fonctionnalité cliente |

Un joueur qui rejoint un serveur CheeseDash sans avoir le mod installé ne voit aucune
différence et n'est averti de rien. Il n'y a aucune vérification côté client et aucune
poignée de main.

### Ports

| Port | Protocole | Configurable |
|------|-----------|---|
| 44311 | HTTP | oui, clé `port` dans `config/cheesedash.json` |
| 44312 | WebSocket | non — il vaut toujours `port + 1`, calculé au démarrage |

Les deux doivent être libres sur l'hôte, et joignables depuis l'endroit où tu ouvres le
navigateur. Chez un hébergeur loué, un port dans cette plage n'est souvent pas ouvert
par défaut ; lis [exposition réseau](auth-and-roles.fr.md#exposition-réseau) avant de
demander son ouverture sur Internet.

---

## Build

CheeseDash n'a aucune dépendance vers un mod frère, donc il se construit seul et il se
construit **en premier** : tous les autres mods de ce dépôt qui s'intègrent au dashboard
compilent contre le jar produit ici.

```bash
cd CheeseDash && ./gradlew build     # gradlew.bat sous Windows
```

Le jar que tu veux :

```text
CheeseDash/build/libs/cheesedash-1.0.0+1.21.1.jar
```

Ignore `cheesedash-1.0.0+1.21.1-sources.jar` — c'est le bundle de sources, pas un mod.
La version exacte vient de `mod_version` dans `gradle.properties`.

Depuis la racine du dépôt, `tools/build-all.sh` construit tout dans l'ordre des
dépendances, ce qui place CheeseDash en premier là aussi.

### Ce que le build fait du frontend

Le HTML, le JavaScript et le CSS du dashboard vivent à la **racine du dossier du mod**,
pas dans `src/main/resources`. La tâche `processResources` les y recopie :

| Source | Arrive dans le jar à |
|---|---|
| `index.html` | `dashboard/index.html` |
| `preview.html` | `dashboard/preview.html` |
| `assets/` | `dashboard/assets/` |

Modifie les fichiers de la racine. Tout ce que tu écris dans
`src/main/resources/dashboard/` sera écrasé au build suivant. Plus de détails dans
[interface web](web-ui.fr.md).

---

## Installer

1. Arrête le serveur.
2. Copie `cheesedash-1.0.0+1.21.1.jar` dans `mods/`.
3. Vérifie que Fabric API est aussi dans `mods/`.
4. Démarre le serveur.
5. Copie le même jar dans le `mods/` de ton client uniquement si tu veux le menu rapide.

Les mods qui enregistrent un module de dashboard (CheeseGuard, CheeseMarket, CheeseKart
et les autres) vont dans le même dossier `mods/`. L'ordre de chargement n'a pas
d'importance : CheeseDash collecte les modules via le système d'entrypoints de Fabric,
qui s'exécute après la découverte de tous les jars.

---

## Ce que crée le premier démarrage

Un fichier :

```text
config/cheesedash.json
```

Il est écrit avec un port, un master token de 32 octets fraîchement généré,
`enabled: true`, un tableau `accounts` vide et un tableau `zoneFolders` vide. Référence
complète dans [configuration](configuration.fr.md).

Trois autres fichiers apparaissent plus tard, chacun seulement quand quelque chose y
écrit :

| Fichier | Créé quand |
|---|---|
| `config/cheesedash_audit.json` | à la première action auditée — y compris le premier login |
| `config/cheesedash_schedules.json` | au premier enregistrement récurrent sauvegardé |
| `config/cheesedash_webhooks.json` | à la création du premier webhook Discord |

Côté client, `config/cheesedash_shortcuts.json` apparaît la première fois que tu
sauvegardes un raccourci du menu rapide.

Rien n'est stocké dans les données du monde ni dans celles des joueurs. Les sessions ne
vivent qu'en mémoire et disparaissent au redémarrage, ce qui est volontaire : se
reconnecter ne coûte rien.

---

## Vérifier le chargement

Le log du serveur, dans l'ordre :

```text
[CheeseDash] Initializing...
[CheeseDash] 0 module(s) discovered.
[CheeseDash] ServerScheduler initialized — 0 recurring schedule(s)
[CheeseDash] DiscordWebhookManager loaded — 0 webhook(s)
[CheeseDash] Initialization complete.
```

C'est un premier démarrage. À partir du deuxième, le fichier de config existe et une
ligne `[CheeseDash] Config loaded — port: 44311, enabled: true, accounts: 0` apparaît
après le nombre de modules, suivie de `[CheeseDash] Loaded N audit entries` dès qu'un
fichier d'audit existe.

Puis, une fois le monde chargé :

```text
[CheeseDash] Server started — notifying 0 module(s)...
[CheeseDash] ✅ Started on port 44311 — http://localhost:44311/
[CheeseDash] WebSocket started on port 44312
```

La ligne `✅ Started on port` est celle qui compte. Si l'initialisation réussit mais que
cette ligne n'apparaît jamais, c'est soit que `enabled` vaut `false` dans la config — et
tu obtiens alors `Disabled in config, skipping.` — soit que le bind a échoué, ce qui
donne :

```text
[CheeseDash] ❌ Failed to start: Address already in use
```

Voir [dépannage](troubleshooting.fr.md#le-dashboard-ne-démarre-jamais-et-le-log-dit-address-already-in-use).

À un véritable premier démarrage, l'encadré du token est affiché aussi — pendant
l'initialisation, juste après le nombre de modules — et c'est la seule fois où le token
apparaît dans le log :

```text
[CheeseDash] ╔═══════════════════════════════════════════════════╗
[CheeseDash] ║       FIRST RUN — Dashboard token generated       ║
[CheeseDash] ║  Save this token, you'll need it to log in:       ║
[CheeseDash] ║   T3Kq...                                         ║
[CheeseDash] ║  Port: 44311                                         ║
[CheeseDash] ╚═══════════════════════════════════════════════════╝
```

Si tu le rates, le token est dans `config/cheesedash.json`, clé `token`.

### Avec des modules installés

Chaque module s'annonce deux fois — une fois depuis le registre, une fois depuis le
chargeur :

```text
[CheeseDash] Module registered: CheeseKart (prefix: /api/v1/kart)
[CheeseDash] Module loaded: CheeseKart (kart)
[CheeseDash] 1 module(s) discovered.
```

Un module dont le préfixe entre en collision avec un préfixe déjà enregistré est ignoré
avec un avertissement et n'apparaîtra pas dans la barre latérale :

```text
[CheeseDash] Duplicate module prefix 'kart' — skipping CheeseKart
```

### Depuis l'extérieur du jeu

`/ping` n'est pas exempté d'authentification — la seule route de `/api/` qui l'est, c'est
`POST /auth/login` —, donc même la vérification la moins chère demande le token :

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/ping
```

```json
{"status":"ok","mod":"cheesedash"}
```

Sans l'en-tête, tu obtiens `401 {"error":"Unauthorized"}`, ce qui prouve quand même que
c'est bien CheeseDash qui répond sur ce port (et compte comme un échec d'authentification
pour le [limiteur de débit](auth-and-roles.fr.md#limitation-de-débit)). Confirme ensuite
la liste des modules :

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/modules
```

```json
[{"prefix":"kart","displayName":"CheeseKart","icon":"🏎","description":"Mode kart et courses"}]
```

Un tableau vide signifie que CheeseDash a chargé et n'a trouvé aucun module — correct si
c'est le seul mod que tu as installé, problématique sinon. Voir
[dépannage](troubleshooting.fr.md#un-module-est-installé-mais-absent-de-la-barre-latérale).

---

## Désinstaller

1. Arrête le serveur. Il n'y a aucun démontage à lancer avant : les serveurs HTTP et
   WebSocket s'arrêtent sur `SERVER_STOPPING`, et rien de ce que possède CheeseDash ne
   vit dans le monde.
2. Retire le jar de `mods/`.
3. Retire tous les mods qui déclarent `cheesedash` dans leur `depends`. CheeseGuard,
   CheeseMarket, CheeseKart, CheeseItems, CobbleCheeseScore, CobblemonDungeons et
   d'autres refuseront de charger sans lui, et Fabric arrêtera le serveur avec une
   erreur de dépendance plutôt que de démarrer en mode dégradé.

Ce qui reste derrière, inerte et que tu peux garder ou supprimer :

```text
config/cheesedash.json            ⚠️ contient le master token — supprime-le ou garde-le secret
config/cheesedash_audit.json      le journal d'audit, jusqu'à 10 000 entrées
config/cheesedash_schedules.json  les définitions de redémarrages récurrents
config/cheesedash_webhooks.json   ⚠️ contient des URL de webhooks Discord, qui sont des secrets
```

Supprimer `cheesedash.json` est la façon d'oublier un master token compromis : le
démarrage suivant en génère un nouveau. Le supprimer efface aussi tous les comptes
additionnels, puisqu'ils sont stockés dans le même fichier.

---

← [CheeseDash](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
