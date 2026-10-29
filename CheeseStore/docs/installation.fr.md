[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

**Côté serveur.** Tout ce qui est dans le jar tourne sur le serveur : les commandes, la
file des commandes d'achat, les gestionnaires de cosmétiques, les routes HTTP.
`fabric.mod.json` déclare `"environment": "*"` et seulement un entrypoint `main`, donc
un client peut charger le jar sans erreur, mais il n'en tire rien. Les joueurs n'ont
besoin d'aucun mod pour acheter, pour dépenser des crédits, ni pour utiliser
`/cosmetics`.

Une exception, et elle est réelle : **les capes sont rendues côté client** par un mod
compagnon séparé, `factionwarhud`, qui n'est pas dans ce dépôt. Sans lui, les
cosmétiques de type cape se vendent, s'équipent, et personne ne les voit. Si tu ne
distribues pas ce mod, désactive la catégorie cape avant d'ouvrir ta boutique.

---

## Prérequis

| | Version | Où |
|---|---|---|
| Minecraft | 1.21.1 | — |
| Fabric Loader | ≥ 0.16.0 | [fabricmc.net](https://fabricmc.net/use/installer/) |
| Fabric API | 0.103.0+1.21.1 ou plus récent | [modrinth.com/mod/fabric-api](https://modrinth.com/mod/fabric-api) |
| Java | 21 | — |
| [CheeseDash](../../CheeseDash/) | build correspondant | ce dépôt, **requis** |
| [CheeseItems](../../CheeseItems/) | build correspondant | ce dépôt, **requis** |

Les deux mods frères sont des dépendances dures dans `fabric.mod.json`. Fabric refuse de
démarrer sans l'un ou l'autre, avec une erreur de dépendance qui nomme l'identifiant
manquant.

CheeseDash fournit le serveur HTTP ; CheeseStore n'en a aucun à lui, donc sans lui il
n'y a ni boutique, ni endpoint de webhook, ni panneau d'administration. CheeseItems
fournit le catalogue et le codex de morphos que les achats de morphos du site lisent et
écrivent, importés directement plutôt que par réflexion.

`sgui` (de Patbox) est embarqué dans le jar en Jar-in-Jar. Ne l'installe pas
séparément.

Et un prérequis que tu fournis toi-même : **un compte Stripe**. Reste en mode test
jusqu'à ce qu'un achat de test ait été livré de bout en bout.

---

## Ordre de build

Les dépendances frères se résolvent par un glob `fileTree` sur le `build/libs/` de
chaque mod, et non par un nom de fichier figé : les deux doivent donc être
**construits** avant que CheeseStore ne compile :

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseItems && ./gradlew build
cd ../CheeseStore && ./gradlew build
```

`tools/build-all.sh` à la racine du dépôt parcourt chaque mod dans l'ordre des
dépendances et c'est la voie la plus simple si tu construis l'ensemble.

Un jar frère manquant se manifeste par une erreur de compilation sur les imports
`com.cheesedash.*` ou `com.cheeseitems.morpho.*`, et non par un message aimable — voir
[dépannage](troubleshooting.fr.md#le-build-échoue-sur-les-imports-comcheesedash-ou-comcheeseitems).

La sortie est :

```text
CheeseStore/build/libs/cheesestore-1.0.0+1.21.1.jar
```

La version vient de `mod_version` dans `gradle.properties`. Ignore
`-sources.jar` ; il n'est pas destiné au serveur.

## Installation

Dépose les trois jars dans le `mods/` du serveur :

```text
mods/
├── cheesedash-<version>+1.21.1.jar
├── cheeseitems-<version>+1.21.1.jar
├── cheesestore-1.0.0+1.21.1.jar
└── fabric-api-<version>.jar
```

Supprime l'ancien jar avant de copier le nouveau. Deux versions du même mod dans
`mods/` est un échec de démarrage, pas un avertissement.

---

## Ce que crée le premier démarrage

Sous `config/` :

| Chemin | Contenu |
|---|---|
| `cheesestore.json` | toutes les clés à leur défaut, clés Stripe vides, `products` et `gallery` vides |
| `cheesestore_playcosmetics.json` | **99 cosmétiques semés**, titres et descriptions en français |
| `cheesestore_wallets.json` | pas créé avant le premier changement de solde |
| `cheesestore_deliveries.json` | pas créé avant la première commande d'achat |
| `cheesestore_ownership.json`, `cheesestore_equipped.json` | créés au premier octroi / équipement |
| `cheesestore_assets/` | créé au premier envoi d'image |

Le catalogue de cosmétiques semé est la seule surprise : tu obtiens d'emblée un
catalogue complet, activé et tarifé, en français, y compris 32 entrées de cape qui
nécessitent le mod client pour être visibles. Passe-le en revue avant d'ouvrir la
boutique. Détails dans
[cosmétiques et portefeuilles](cosmetics.fr.md#le-catalogue-par-défaut).

## Vérifier le chargement

Les lignes de log à chercher, dans l'ordre :

```text
[CheeseStore] Initializing...
[CheeseStore] No wallets file found, starting fresh
[CheeseStore] No cosmetics file found, starting fresh
[CheeseStore] Initialising cosmetics module...
[CheeseStore] No cosmetic catalogue found — writing default catalogue
[CheeseStore] Default cosmetic catalogue written (99 entries)
[CheeseStore] No cosmetic ownership file found, starting fresh
[CheeseStore] No equipped-cosmetics file found, starting fresh
[CheeseStore] TrailManager initialised (render every 2 ticks)
[CheeseStore] NameTagManager initialised (NAME_COLOR + CHAT_TAG via scoreboard teams)
[CheeseStore] PetManager initialised
[CheeseStore] MountManager initialised (combat recall via AFTER_DAMAGE + hurtTime poll)
[CheeseStore] CombatEffectsManager ready (KILL/DEATH effects, AFTER_DEATH fallback)
[CheeseStore] Cape networking initialised (cheesestore:capes)
[CheeseStore] CosmeticsCommands registered (/cosmetics)
[CheeseStore] MountCommand registered (/mount, /monture)
[CheeseStore] Cosmetics module initialised
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked. Set it in config/cheesestore.json or via CheeseDash admin.
[CheeseStore] Initialized successfully!
[CheeseStore] Server started
```

Aux démarrages suivants, le premier bloc affiche à la place
`Config loaded (N products, N gallery items)`, `Loaded N wallet(s)` et
`Loaded N deliveries` (et les fichiers de cosmétiques annoncent `Loaded N …` au lieu de
`No … file found`).

Cet avertissement sur `storeBaseUrl` est **attendu sur une installation neuve** et c'est
la seule ligne sur laquelle tu dois agir avant d'encaisser un paiement. Ce n'est pas du
bruit : sans URL de base, le checkout est bloqué volontairement plutôt que d'envoyer un
client qui paie vers une redirection morte.

Vérifie ensuite en jeu et dans le navigateur :

```mcfunction
/premium
```

```text
🧀 Crédits : 0
```

```mcfunction
/cosmetics
```

Un menu coffre intitulé *Cosmétiques* avec huit boutons de catégorie. Tous les comptes
affichent `0 / N` — le catalogue existe, tu ne possèdes rien.

Et le côté HTTP, avec CheeseDash qui tourne :

```bash
curl http://localhost:44311/store/api/config
```

```json
{"storeName":"Arkensia","storeSubtitle":"Boutique Premium","stripePublishableKey":"","storeEnabled":true,"currencyName":"crédit","oauthEnabled":true}
```

Si cela renvoie `404`, CheeseDash n'a pas récupéré le module — l'entrypoint
`cheesedash` n'est pas lu. Voir
[dépannage](troubleshooting.fr.md#le-module-store-est-absent-du-dashboard).

Visiter `http://localhost:44311/store/` sert la page de boutique elle-même.

---

## Avant d'encaisser un vrai paiement

Parcourir [premiers pas](getting-started.fr.md) couvre cela correctement. La liste
courte :

1. Règle `storeBaseUrl` sur l'origine publique de ta boutique, et sers-la en **HTTPS**
   derrière un reverse proxy. Stripe n'envoie pas de webhooks vers du HTTP clair sur un
   compte de production, et le cookie `store_session` ne porte pas d'attribut `Secure`.
2. Colle tes clés Stripe de **test**, crée un endpoint de webhook pointant vers
   `<storeBaseUrl>/store/api/webhook`, abonne-le à
   `checkout.session.completed`, et colle le `whsec_…` de cet endpoint dans
   `stripeWebhookSecret`. Ce champ vide, le webhook refuse d'agir du tout.
3. Fais un achat de test et vérifie que les commandes se sont exécutées. Pas que le
   paiement a réussi — que les *commandes se sont exécutées*.
4. Lis [SECURITY.md](../../SECURITY.md).
5. Réécris `store/cgv.html`. Les conditions livrées là appartiennent au serveur de
   quelqu'un d'autre.
6. Ne donne à personne un compte `OPERATOR` que tu ne confierais pas avec tes clés
   Stripe : ce rôle peut les écraser, pour la raison exposée dans
   [l'API dashboard](dashboard.fr.md#ce-que-le-cœur-applique-réellement).

---

## Désinstallation

Retire `cheesestore-*.jar` de `mods/` et redémarre. Rien d'autre n'est nécessaire — il
n'y a aucun mixin, aucun bloc ni item enregistré, et aucune donnée de monde.

Ce qui reste derrière dans `config/` :

```text
cheesestore.json                  clés Stripe, produits, galerie — contient des secrets
cheesestore_deliveries.json       historique des commandes d'achat
cheesestore_wallets.json          soldes de crédits
cheesestore_playcosmetics.json    le catalogue de cosmétiques
cheesestore_cosmetics.json        le catalogue historique
cheesestore_ownership.json        qui possède quoi
cheesestore_equipped.json         qui porte quoi
cheesestore_oauth_links.json      liaisons session de navigateur → UUID Minecraft
cheesestore_assets/               les PNG envoyés
```

Supprime-les pour achever le retrait. **Révoque aussi tes clés Stripe depuis le
dashboard Stripe** — supprimer le fichier retire ta copie, pas celle de Stripe, et une
clé qui a atterri dans une sauvegarde reste vivante jusqu'à ce que tu la fasses tourner.

Trois choses dans le monde ne sont pas nettoyées par la désinstallation, parce que les
gestionnaires qui les retireraient ne sont plus là : les familiers et montures invoqués,
étiquetés `cos_pet` et `cos_mount`, et toute équipe de scoreboard de tag de chat ou de
couleur de pseudo encore présente. Nettoie les entités porteuses des étiquettes avant de
retirer le mod :

```mcfunction
/kill @e[tag=cos_pet]
/kill @e[tag=cos_mount]
```

Les tags de chat et les couleurs de pseudo sont des équipes de scoreboard par joueur,
nommées `cos_` suivi des 12 premiers chiffres hexadécimaux de l'UUID. Elles vivent dans
les données de scoreboard du monde, pas dans le fichier du joueur, et sont supprimées à
la déconnexion du joueur — un arrêt propre n'en laisse donc aucune, mais un crash peut
en laisser. Liste-les avec `/team list` et supprime toute équipe `cos_…` avec
`/team remove <nom>`.

---

← [CheeseStore](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
