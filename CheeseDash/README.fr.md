# CheeseDash

> 🇬🇧 [Read in English](README.md)

> Dashboard web auto-hébergé pour administrer un serveur Fabric — et le hub auquel les
> autres mods de ce dépôt se branchent.

CheeseDash démarre un serveur HTTP et un serveur WebSocket à l'intérieur du processus
de ton serveur Minecraft, et leur fait servir une interface d'administration en une
seule page. Les autres mods s'enregistrent auprès de lui comme **modules**, via un
entrypoint Fabric personnalisé ; chaque module reçoit son propre espace d'API sous
`/api/v1/<prefix>/` et, pour les modules que le frontend fourni connaît, sa propre
section dans la barre latérale. Une section n'apparaît que si son module est réellement
installé, donc toutes les combinaisons fonctionnent : CheeseDash seul, avec un module, ou
avec tous les modules de ce dépôt.

Un administrateur se connecte avec un token, arrive sur une page de vue d'ensemble avec
le TPS et le nombre de joueurs, et de là modifie des zones, lance une commande serveur,
planifie un redémarrage ou lit le journal d'audit. Un compte `READ_ONLY` voit les mêmes
pages, moins la section *Administration*, et ne peut rien modifier.

La règle de conception derrière tout ça : **les administrateurs utilisent le dashboard,
les joueurs utilisent les interfaces in-game.** CheeseDash n'enregistre **aucune
commande** — il n'y a rien à taper dans le chat, et il n'existe volontairement aucune
interface d'administration en jeu. La seule pièce côté client est un menu rapide
optionnel associé à une touche, prévu pour déclencher quelques commandes raccourcis
sans ouvrir un navigateur.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 fichiers Java, ~4 650 lignes** (plus environ
36 000 lignes de HTML, JavaScript et CSS pour l'interface)

---

## Prérequis

| | Version | Pourquoi |
|---|---|---|
| Minecraft | **1.21.1** | déclaré en `~1.21.1` |
| Fabric Loader | ≥ 0.15.11 | |
| [Fabric API](https://modrinth.com/mod/fabric-api) | n'importe quel build 1.21.1 | réseau et événements de cycle de vie |
| Java | 21 ou plus | le serveur HTTP du JDK, et `release = 21` |

Aucun autre mod n'est requis. CheeseDash est utile seul et ne dépend ni de Cobblemon ni
de quoi que ce soit d'autre dans ce dépôt. La bibliothèque WebSocket
(`org.java-websocket:Java-WebSocket:1.5.7`) est embarquée dans le jar.

## Installation

```bash
cd CheeseDash && ./gradlew build     # gradlew.bat sous Windows
```

1. Dépose `build/libs/cheesedash-1.0.0+1.21.1.jar` dans `mods/`, à côté de Fabric API.
2. Démarre le serveur. Il écrit `config/cheesedash.json` avec un master token fraîchement
   généré, et affiche ce token dans un encadré dans le log.
3. Ouvre `http://<serveur>:44311` et connecte-toi avec ce token.

| Port | Protocole | Rôle |
|------|-----------|------|
| 44311 | HTTP | dashboard et API REST |
| 44312 | WebSocket | TPS en direct, événements joueurs, décompte de redémarrage, flux d'audit — master token uniquement, voir [WebSocket](docs/websocket.fr.md) |

Le port WebSocket vaut toujours le port HTTP plus un. Il n'a pas de réglage propre.

> ⚠️ **N'expose pas ces ports sur Internet.** Il n'y a pas de TLS : le token et chaque
> identifiant de session circulent en clair, et le rôle `SUPER_ADMIN` peut exécuter
> n'importe quelle commande serveur. Restreins les ports avec un pare-feu, atteins-les
> par un tunnel SSH ou un VPN, ou place devant un reverse proxy qui termine le TLS. Le
> raisonnement complet est dans
> [authentification et rôles](docs/auth-and-roles.fr.md#exposition-réseau), et la
> politique du dépôt dans [SECURITY.md](../SECURITY.md).

## Essayer l'interface sans serveur

`preview.html` est une ancienne maquette autonome du dashboard, en français. Ouvre-le
directement depuis le disque — sans build, sans Minecraft ; le navigateur a seulement
besoin des CDN — et il s'ouvre directement sur la vue d'ensemble, le formulaire de
connexion étant sauté. Seule cette vue d'ensemble a des données factices ; les autres
entrées affichent un écran provisoire `Phase 2 — implémentation en cours`. Il donne une
idée de l'apparence, pas des vraies vues, qui demandent un serveur qui tourne. Détails
dans [interface web](docs/web-ui.fr.md#previewhtml--une-ancienne-maquette-autonome).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis exacts, ordre de build, ce que crée le premier démarrage, les lignes de log qui prouvent le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un parcours depuis un serveur vierge jusqu'à un dashboard connecté, un second compte, et une commande lancée depuis le navigateur |
| [Configuration](docs/configuration.fr.md) | Chaque clé de `config/cheesedash.json`, et les autres fichiers écrits par le mod |
| [Authentification et rôles](docs/auth-and-roles.fr.md) | Le flux de login, les sessions de 24 h à expiration glissante, ce que peut faire chacun des trois rôles, la limitation de débit, les en-têtes de sécurité, l'exposition réseau |
| [API REST](docs/rest-api.fr.md) | Chaque route du cœur : méthode, chemin, paramètres, corps, réponse, codes de statut, rôle requis — et comment les routes des modules sont dispatchées |
| [WebSocket](docs/websocket.fr.md) | La poignée de main, les canaux, chaque type de message et son payload |
| [Écrire un module](docs/writing-a-module.fr.md) | Comment un autre mod se branche : l'entrypoint, l'interface `DashboardModule`, les helpers, la règle de thread, et un exemple complet compilable |
| [Interface web](docs/web-ui.fr.md) | Comment le frontend est construit et où le modifier |
| [Planificateur](docs/scheduler.fr.md) | Redémarrages, arrêts et fenêtres de maintenance, ponctuels ou récurrents |
| [Webhooks Discord](docs/discord-webhooks.fr.md) | Acheminer les événements du serveur et des modules vers des salons Discord |
| [Journal d'audit](docs/audit.fr.md) | Ce qui est enregistré, où c'est stocké, comment le relire |
| [Menu rapide](docs/quick-menu.fr.md) | L'écran de raccourcis optionnel côté client |
| [Architecture](docs/architecture.fr.md) | Les 16 classes, qui possède quoi, le modèle de threads, la persistance, et les faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Port déjà pris, token refusé, module absent, 503, session expirée, erreurs de compilation |

## Commandes

Aucune. CheeseDash n'enregistre aucune commande Brigadier, aucun alias et aucun écran
d'administration en jeu. L'administration se fait dans le navigateur, et une commande
serveur que tu veux lancer de là passe par `POST /api/v1/server/command`.

La moitié cliente du mod enregistre une touche, **Ouvrir le menu rapide CheeseDash**,
non assignée par défaut et utilisable uniquement par les opérateurs (niveau de
permission 2). Voir [menu rapide](docs/quick-menu.fr.md).

## L'API du cœur en un coup d'œil

Tout vit sous `/api/v1/`. `POST /auth/login` est la seule route qui n'exige pas
`Authorization: Bearer <token>`.

| Méthode | Endpoint | Rôle |
|---------|----------|------|
| GET | `/ping` | test de santé, ne touche à aucun état du jeu |
| GET | `/status` | TPS, nombre de joueurs, MOTD, version, nombre de modules |
| GET | `/modules` | quels modules sont chargés — c'est ce qui construit la barre latérale |
| GET | `/server/stats` · `/server/logs?n=` · `/server/mods` | introspection du serveur |
| POST | `/server/command` | lancer une commande Minecraft, auditée, sur le thread serveur |
| POST | `/auth/login` · `/auth/logout` · GET `/auth/session` | sessions |
| GET/POST/PUT/DELETE | `/admin/accounts` | gestion des comptes, `SUPER_ADMIN` uniquement |
| GET | `/admin/audit` · `/admin/sessions` | journal d'audit, sessions actives |
| GET/POST/DELETE | `/server/schedule` | redémarrages et fenêtres de maintenance |
| GET/POST/PUT/DELETE | `/webhooks` | acheminement des webhooks Discord |

Référence complète dans [API REST](docs/rest-api.fr.md).

## Spécificités Arkensia

Quelques libellés et les modèles de messages Discord par défaut mentionnent Arkensia, le
serveur pour lequel ce mod a été écrit. C'est cosmétique. Change-les dans
`DiscordWebhookManager.java` et dans les fichiers de langue sous
`src/main/resources/assets/cheesedash/lang/`.

Les routes `/dashboard/zone-folders` servent à regrouper visuellement les zones de
CheeseGuard dans la barre latérale. Elles sont stockées dans la config de CheeseDash et
n'ont aucun effet sur le comportement de CheeseGuard ; sans CheeseGuard installé, elles
sont du poids mort inoffensif.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE) avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur monétisé ; tu ne peux
pas vendre le logiciel.

## Support

- **Bugs et demandes de fonctionnalités** → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- **Questions et discussions** → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
- **Problèmes de sécurité** → [SECURITY.md](../SECURITY.md). N'ouvre pas d'issue
  publique pour une vulnérabilité.
