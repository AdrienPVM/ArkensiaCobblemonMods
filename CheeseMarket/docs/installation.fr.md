[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CheeseMarket est **requis des deux côtés**. Le serveur possède les données de boutiques et
la logique d'achat ; le client dessine l'interface plein écran. `fabric.mod.json` déclare
`"environment": "*"` et un entrypoint `client`, et le panneau de boutique est une classe
`Screen` côté client. Installe-le uniquement côté serveur et le serveur enverra joyeusement
des payloads `shop_open` qu'aucun client ne saura traiter : rien ne s'ouvre, et aucune
erreur n'est affichée.

---

## Prérequis

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Fabric Loader** | ≥ 0.15.11 (`fabricloader` dans `depends`) |
| **Fabric API** | n'importe quelle version pour 1.21.1 — [télécharger](https://modrinth.com/mod/fabric-api) |
| **Java** | 21 ou plus récent |
| **[CheeseDash](../../CheeseDash/)** | **requis** — déclaré dans `depends`, le mod ne se charge pas sans lui |

Facultatifs, chacun détecté à l'exécution par réflexion :

| | |
|---|---|
| [CheeseStore](../../CheeseStore/) | permet de fixer des prix en fromage (`cheesestore:fromage`) |
| [CheeseItems](../../CheeseItems/) | permet de vendre des items de quête personnalisés avec leurs propres icônes, et active le catalogue morpho |
| [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) | permet des prix en pièces, payés depuis la bourse du joueur |

CheeseStore et CheeseItems sont listés sous `suggests`. Numismatic Overhaul n'est pas listé
du tout, alors que le mod réfléchit dedans par son identifiant de mod — Fabric ne
t'avertira donc pas s'il manque, et les prix en pièces seront simplement impayables.

---

## Ordre de construction

CheeseMarket compile contre le jar de CheeseDash, résolu par un glob plutôt que par un nom
de fichier figé :

```groovy
modCompileOnly files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

Donc **CheeseDash doit être construit d'abord**, sinon le glob ne correspond à rien et les
imports `com.cheesedash` de `MarketApi` et `CheeseMarketDashboardModule` ne se résolvent
pas.

```bash
cd CheeseDash      && ./gradlew build
cd ../CheeseMarket && ./gradlew build
```

`tools/build-all.sh` parcourt tout le dépôt dans le bon ordre si tu préfères ne pas y
penser.

Le résultat est `CheeseMarket/build/libs/cheesemarket-1.0.0+1.21.1.jar`. La version vient
de `mod_version` dans `gradle.properties` ; si tu la changes, le nom du jar change avec.
Ignore le `-sources.jar` posé à côté.

---

## Installer

Dépose les deux jars dans le `mods/` du serveur :

```text
mods/
├── cheesedash-1.0.0+1.21.1.jar
├── cheesemarket-1.0.0+1.21.1.jar
└── fabric-api-*.jar
```

Puis le même jar `cheesemarket` — et Fabric API — dans le `mods/` du client. Le client n'a
pas besoin de CheeseDash.

---

## Ce que le premier démarrage crée

Aucun fichier de configuration. Rien n'est écrit dans `config/`. Tout atterrit dans la
sauvegarde du monde :

```text
<world>/data/cheesemarket/
├── shops/        créé au SERVER_STARTED, vide
├── assets/       créé au premier usage du dashboard
└── templates/    créé au premier usage du dashboard
```

`cheesemarket_stock.json` et `cheesemarket_transactions.json` apparaissent au premier
achat, pas avant.

Aucune boutique n'est créée par défaut. Une installation neuve a une liste de boutiques
vide, et `/shop list` répond `Aucun shop trouvé.`

⚠️ Garde `level-name=world` dans `server.properties` à moins d'être prêt à modifier le mod.
`ShopManager` résout son dossier depuis le vrai chemin de sauvegarde, mais
`AssetManager` retombe sur un `world` codé en dur parce que rien n'appelle sa méthode
`init` — donc avec un `level-name` différent, les images téléversées partent là où le reste
du mod ne regarde jamais. Détails dans
[l'éditeur de mise en page](layout-editor.fr.md#les-images-téléversées).

---

## Vérifier que c'est chargé

Dans le log du serveur, au démarrage :

```text
[CheeseMarket] Initialized.
```

Cette ligne est affichée avec `System.out.println` depuis `onInitialize` : elle peut donc
ne pas porter de préfixe de logger selon ta configuration de journalisation.

Puis, une fois le monde lancé, depuis le logger `CheeseMarket` :

```text
[Market] Loading shops from .../world/data/cheesemarket/shops
```

et une ligne par boutique trouvée :

```text
[Market]   Loaded shop 'general': jsonHasLayout=true, parsedLayout=5 elements
```

Côté client :

```text
[CheeseMarket] Client initialized.
```

Chaque intégration facultative journalise son verdict la première fois qu'on la sollicite,
c'est-à-dire d'ordinaire à la première construction d'une vue de boutique plutôt qu'au
démarrage :

```text
[Market] CheeseStore detected — fromage support enabled
[Market] Numismatic Overhaul not found — purse support disabled
[Market] CheeseItems detected — custom item icon support enabled
```

### En jeu

```mcfunction
/shop list
```

Si la commande existe et répond — même avec `Aucun shop trouvé.` — le côté serveur est
chargé. Puis :

```mcfunction
/shop create test
/shop open test
```

Un panneau de boutique vide sur fond sombre signifie que les deux côtés fonctionnent. Si la
seconde commande n'affiche rien et que rien ne s'ouvre, le mod manque côté client.

### Dashboard

Ouvre CheeseDash sur le port 44311 et cherche **CheeseMarket 🛒** dans la barre latérale,
ou appelle directement l'API :

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/market/stats
```

```json
{"shopCount":1,"totalSets":0,"totalItems":0,"enabledItems":0,"transactionCount":0}
```

Un `404` sur ce chemin signifie que CheeseDash n'a pas récupéré le module — vérifie que
les deux jars se sont chargés et que CheeseDash journalise bien le module au démarrage.

---

## Désinstaller

1. Retire `cheesemarket-*.jar` du `mods/` du serveur et de celui de chaque client.
2. Redémarre.

Ce qui reste derrière, dans la sauvegarde du monde :

```text
<world>/data/cheesemarket/     boutiques, stock, transactions, assets, templates
```

Rien dans `config/`. Rien dans le NBT des joueurs, rien dans les données propres du
monde — le mod n'enregistre aucun item, aucun bloc et aucune entité, donc un monde ouvert
sans lui n'affiche aucun avertissement de contenu manquant.

Les soldes de fromage et de pièces appartiennent à CheeseStore et à Numismatic Overhaul et
ne sont pas touchés par le retrait de ce mod. Les items déjà vendus restent dans les
inventaires des joueurs.

Supprime `<world>/data/cheesemarket/` pour effacer aussi les définitions de boutiques et
l'historique des ventes. C'est irréversible, et c'est le seul endroit où les boutiques
existent — garde une copie s'il y a la moindre chance que tu réinstalles.

---

← [CheeseMarket](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
