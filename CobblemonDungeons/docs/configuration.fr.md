[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

Tout vit sous `config/cobblemon_dungeons/`, créé au premier démarrage :

```text
config/cobblemon_dungeons/
├── config.json              configuration principale — le bloc base de données, et rien d'autre
├── dungeon_tickets.json     modèles de tickets
├── dungeons/
│   └── example_dungeon.json un fichier par donjon, écrit au premier démarrage
├── trainers/
│   └── example_trainer.json un fichier par dresseur Gardien, écrit au premier démarrage du serveur
├── run_history.json         apparaît après la première partie terminée
├── cooldowns.json           apparaît après la première partie réussie
└── leaderboard.json         apparaît après la première partie réussie
```

Le chemin vient de
`FabricLoader.getInstance().getConfigDir().resolve("cobblemon_dungeons")` : il suit donc le
dossier de configuration du serveur où qu'il soit.

Cette page couvre `config.json` et la sémantique du rechargement. Les fichiers de donjons
ont leur propre page — [la définition d'un donjon](dungeons.fr.md) — comme
[les tickets](tickets.fr.md) et [les dresseurs](trainers.fr.md), parce que chacun est assez
vaste pour en mériter une.

---

## `config.json`

Une seule section. Le mod n'a aucun réglage de comportement en dehors des fichiers de
donjons : pas d'interrupteur global, pas de drapeau de débogage qui survive à un
redémarrage, aucun bouton de réglage ici.

```json
{
  "database": {
    "useSql": false,
    "host": "localhost",
    "port": 3306,
    "database": "minecraft",
    "username": "root",
    "password": "password",
    "tablePrefix": "cobblemon_dungeons_"
  }
}
```

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `database.useSql` | boolean | `false` | Interrupteur principal de la connexion MariaDB |
| `database.host` | string | `"localhost"` | Hôte de la base |
| `database.port` | int | `3306` | Port de la base |
| `database.database` | string | `"minecraft"` | Nom du schéma |
| `database.username` | string | `"root"` | Utilisateur de la base |
| `database.password` | string | `"password"` | Mot de passe de la base |
| `database.tablePrefix` | string | `"cobblemon_dungeons_"` | Préfixe de la table créée par ce mod |

⚠️ `password` est livré avec la chaîne littérale `"password"`. C'est l'initialiseur du champ
`MainConfig.DatabaseConfig.password` dans
`src/main/java/com/sebastian/cobblemondungeons/config/MainConfig.java`, et il est écrit tel
quel dans `config.json` au premier démarrage du serveur. C'est un bouchon, pas un secret, et
il doit être remplacé par ton véritable mot de passe de base de données — ou laissé en place
seulement parce que `useSql` vaut `false` et qu'aucune connexion n'est jamais tentée. Ne
mets pas ce serveur en service avec la valeur par défaut et `useSql: true`.

Deux autres choses méritent d'être dites tout haut à propos de ce bloc. Le fichier est du
texte en clair dans le dossier de configuration : quiconque peut lire ta configuration peut
lire le mot de passe ; garde l'utilisateur de la base limité au seul schéma dont il a
besoin. Et `GET /api/v1/dungeons/config` masque `password` en `***` mais laisse `host`,
`port`, `database` et `username` visibles pour toute session de dashboard authentifiée.

### À quoi sert réellement la base de données

À exactement une chose : porter l'état sérialisé d'un joueur entre un serveur principal et
un serveur de donjon en mode `CROSS_SERVER`. À l'initialisation, le mod crée une seule
table :

```sql
CREATE TABLE IF NOT EXISTS cobblemon_dungeons_player_sync (
  uuid VARCHAR(36) PRIMARY KEY,
  data MEDIUMBLOB,
  last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
)
```

`data` est un compound NBT compressé en GZip. Rien d'autre n'y est stocké : historique des
parties, classements et cooldowns sont des fichiers JSON quel que soit ce réglage.

Avec `useSql: true`, le PC Cobblemon est écrit en base et retiré du payload de transfert,
parce qu'un PC complet est trop gros pour passer dans un message de plugin. Avec
`useSql: false`, le payload transporte tout, PC compris, ce qui fonctionne pour des tailles
de PC ordinaires et constitue le chemin supporté sur un seul serveur.

### Comportement de la connexion

HikariCP, avec le pilote MariaDB (`org.mariadb.jdbc.Driver`, compatible MySQL), un pool de
10 connexions et un délai de connexion de 5 secondes. HikariCP et le pilote sont tous deux
embarqués dans le jar : rien à installer à côté.

Un échec au démarrage n'est pas fatal : l'erreur est journalisée sous *Failed to initialize
database. Player data transfer via SQL will be disabled.*, le pool est abandonné, et le mod
tourne comme si `useSql` valait `false`. `/dungeons sql test` rapporte le message d'erreur
conservé.

La connexion est ouverte une fois dans `onInitialize` et fermée sur `SERVER_STOPPING`.
**Modifier cette section exige un redémarrage du serveur** : rien ne la relit, et la route
de rechargement du dashboard reconstruit l'objet `MainConfig` en mémoire sans toucher au
pool en service.

## Le rôle du serveur

Le seul réglage qui ne se trouve dans aucun fichier de configuration.
`cobblemon.dungeons.serverRole` comme propriété système JVM, ou
`COBBLEMON_DUNGEONS_SERVER_ROLE` comme variable d'environnement :

```bash
java -Dcobblemon.dungeons.serverRole=dungeon -Xmx8G -jar fabric-server-launch.jar nogui
```

La valeur `dungeon`, sans distinction de casse, marque un serveur de donjon. Toute autre
valeur — y compris une variable absente — marque un serveur principal. La propriété est lue
d'abord et la variable d'environnement seulement en repli.

Le résultat est mis en cache dans un `volatile Boolean` au premier appel et journalisé une
fois :

```text
Cobblemon Dungeons server role: dungeon
```

Le changer exige un redémarrage. Ce que le rôle change est documenté dans
[le cycle de vie des instances](instances.fr.md#les-parties-cross-serveur) : sur un serveur
de donjon, `/dungeons` et tout l'arbre `/DungeonsParty` sont refusés, et le nettoyage des
entités est total plutôt que sélectif.

Sur une installation à serveur unique, laisse-le non défini.

## Ce qui se recharge et ce qui ne se recharge pas

| Modification | Prise en compte par |
|---|---|
| `dungeons/*.json` | `POST /api/v1/dungeons/configs/reload`, ou un redémarrage |
| `dungeon_tickets.json` | Le même rechargement pour le serveur ; les clients connectés seulement à leur prochaine connexion |
| `run_history.json`, `cooldowns.json`, `leaderboard.json` | Le même rechargement — mais une partie qui se termine avant écrasera ton édition |
| `trainers/*.json` | **Un redémarrage seulement.** `TrainerLoader.load` tourne sur `SERVER_STARTED` et rien ne le relance |
| `config.json` | **Un redémarrage seulement** pour la connexion à la base |
| Rôle du serveur | **Un redémarrage seulement** |

Le chemin de rechargement est `ConfigLoader.load()`. Il vide d'abord le registre de donjons,
de sorte qu'un donjon dont tu as supprimé le fichier disparaît, et qu'un fichier renommé se
réenregistre sous le `general.id` qu'il contient. Les instances en cours ne sont pas
affectées : chacune conserve l'objet `DungeonConfig` avec lequel elle a été construite, donc
un rechargement en pleine partie ne change pas les règles sous les pieds d'un groupe.

## Un exemple complet annoté

Le JSON standard n'admet pas de commentaires, mais `ConfigLoader` lit ce fichier avec
`Gson.fromJson`, qui est permissif et ignore les lignes `//` : les annotations ci-dessous
peuvent donc rester dans le fichier. Le mod n'écrit `config.json` que s'il est absent, elles
ne sont donc pas effacées ensuite non plus. Retire-les si un autre outil doit lire le
fichier. La configuration principale avec la base de données activée :

```json
{
  "database": {
    // N'active ceci que pour une vraie installation multi-serveurs derrière un proxy.
    // Sur un seul serveur, laisse false : le payload de connexion transporte tout.
    "useSql": true,

    // Joignable depuis chaque serveur Minecraft qui fera tourner des donjons.
    "host": "10.0.0.12",
    "port": 3306,

    // Le schéma doit déjà exister ; le mod y crée sa table,
    // pas le schéma lui-même.
    "database": "cacademy",

    // Donne à cet utilisateur des droits sur ce schéma uniquement.
    "username": "dungeons",

    // Remplace le bouchon "password" livré avec le mod. C'est la seule ligne
    // qui ne doit pas garder sa valeur par défaut.
    "password": "un-vrai-mot-de-passe-issu-de-ton-gestionnaire",

    // Préfixe de la seule table créée : <préfixe>player_sync.
    // À changer seulement si deux installations partagent un schéma.
    "tablePrefix": "cobblemon_dungeons_"
  }
}
```

Pour une installation à serveur unique, tout le fichier peut rester exactement tel qu'il a
été généré — `useSql: false` et le reste ignoré. C'est la configuration que veulent la
plupart des serveurs, et le mot de passe bouchon y est inoffensif.

Un fichier de donjon complet et annoté se trouve dans
[la définition d'un donjon](dungeons.fr.md) ; le `dungeons/example_dungeon.json` généré est
lui-même un exemple travaillé avec tous les champs récents remplis, et le lire à côté de
cette page est le moyen le plus rapide d'apprendre le format.

---

← [Commandes](commands.fr.md) · [La définition d'un donjon](dungeons.fr.md) →
