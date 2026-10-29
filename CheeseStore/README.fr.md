# CheeseStore

> 🇬🇧 [Read in English](README.md)

> Une boutique web avec paiement Stripe, livraison en jeu, une monnaie de crédits et
> un catalogue de cosmétiques jouables.

> ## ⚠️ Ce mod encaisse de vrais paiements
>
> Il crée des sessions Stripe Checkout, vérifie les webhooks et exécute des commandes
> serveur au moment du paiement. Il est fourni **tel quel, sans aucune garantie**,
> comme l'indique la [LICENSE](../LICENSE). Il n'a fait l'objet d'aucun audit
> indépendant et ne revendique aucune conformité PCI-DSS.
>
> **Ni l'auteur ni cette documentation ne garantissent qu'une boutique exploitée avec
> ce mod est conforme, légalement ou fiscalement, où que ce soit.** Le droit de la
> consommation, la TVA, la facturation, les obligations de remboursement et le droit
> de rétractation sont à ta charge, et ils diffèrent d'une juridiction à l'autre. Les
> conditions générales de vente livrées dans `store/cgv.html` ont été écrites pour un
> serveur précis et **doivent être réécrites par l'exploitant** — les publier telles
> quelles est pire que de n'en avoir aucune.
>
> Lis [SECURITY.md](../SECURITY.md) avant d'encaisser le moindre paiement. Puis lis
> [le flux de paiement](docs/payments.fr.md), qui est la page qui compte.

Un acheteur ouvre une page web, choisit un pack, paie par carte sur le checkout hébergé
par Stripe, et une commande s'exécute sur ton serveur. C'est tout le produit. Le mod
tient le catalogue, crée la session Stripe, vérifie la signature du webhook, enregistre
la commande et exécute les commandes de livraison — en réessayant à la prochaine
connexion de l'acheteur s'il était hors ligne au moment du paiement.

À côté du parcours carte bancaire, il existe une seconde économie qui ne te coûte rien
à exploiter : un solde de crédits par joueur, rechargé par la commande de livraison
d'un produit, dépensé dans un catalogue de 63 cosmétiques jouables — traînées de
particules, effets de kill et de mort, tags de chat, couleurs de pseudo, familiers,
montures chevauchables et capes. Les joueurs les parcourent et les équipent depuis un
menu coffre avec `/cosmetics` ; un emplacement par catégorie, et une monture invoquée
est rappelée automatiquement dès qu'un combat commence.

Les administrateurs ne tapent aucune commande pour tout cela. Produits, commandes,
relivraison manuelle, soldes de portefeuille et clés Stripe vivent dans le panneau
CheeseDash.

**Minecraft 1.21.1 · Fabric · Java 21 · 32 fichiers source, ~8 100 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | [CheeseDash](../CheeseDash/) · [CheeseItems](../CheeseItems/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Requis pour les capes** | `factionwarhud`, côté client — absent de ce dépôt |
| **Lié** | `factionwar` déclenche les effets de kill et de mort par réflexion — absent de ce dépôt |
| **Embarqué** | `sgui` de Patbox, en Jar-in-Jar ; aucune installation séparée |

Côté serveur. Les joueurs n'ont besoin d'aucun mod pour acheter, dépenser ou équiper —
à une seule exception près, les capes, rendues par ce mod client compagnon. Sans lui,
les huit capes du catalogue par défaut se vendent, s'équipent et restent invisibles.
Désactive la catégorie si tu ne distribues pas ce mod.

Plus un compte Stripe à toi. Reste sur les clés de test jusqu'à ce qu'un achat de test
ait été livré de bout en bout.

## Installation

```bash
cd CheeseDash   && ./gradlew build     # CheeseStore compile contre son jar
cd ../CheeseItems && ./gradlew build   # et contre celui-ci
cd ../CheeseStore && ./gradlew build
```

Dépose `build/libs/cheesestore-1.0.0+1.21.1.jar` et les deux jars frères dans le
`mods/` du serveur. Au premier démarrage, le mod écrit `config/cheesestore.json` avec
des clés Stripe vides, sème un catalogue de 63 cosmétiques, et journalise le seul
avertissement sur lequel tu dois agir :

```text
[CheeseStore] Default cosmetic catalogue written (63 entries)
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
[CheeseStore] Initialized successfully!
```

Le checkout reste bloqué jusqu'à ce que `storeBaseUrl` désigne une vraie origine
publique — délibérément, parce qu'un acheteur redirigé vers `localhost` après avoir
payé n'arrive nulle part.

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de build, ce que crée le premier démarrage, les lignes de log qui prouvent le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge jusqu'à un vrai paiement en mode test qui livre des crédits dans ton propre portefeuille |
| [Commandes](docs/commands.fr.md) | `/premium`, `/balance`, `/store link`, `/cosmetics`, `/mount` — chaque argument, niveau de permission, sortie et message d'échec |
| [Configuration](docs/configuration.fr.md) | `cheesestore.json` en entier : clés Stripe, réglages boutique, champs produit, ce qui s'applique à chaud et ce qui exige un redémarrage, et quelles clés sont mortes |
| [Le flux de paiement](docs/payments.fr.md) | De la session de checkout à la commande livrée, étape par étape, avec ce qui se passe quand chacune échoue |
| [Cosmétiques et portefeuilles](docs/cosmetics.fr.md) | La monnaie de crédits, les deux catalogues de cosmétiques, les huit catégories, posséder contre équiper |
| [API dashboard](docs/dashboard.fr.md) | Chaque route REST sous `/api/v1/store` et `/store/api`, la séparation admin/public, les vraies limites de débit, les messages WebSocket |
| [Architecture](docs/architecture.fr.md) | Organisation des packages, propriété de l'état, threading et points de bascule, couplage inter-mods, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — en commençant par « un acheteur a payé et n'a rien reçu » |

## Les commandes en un coup d'œil

| Commande | Niveau | Effet |
|---|---|---|
| `/premium` · `/balance` | tous | ton propre solde de crédits |
| `/premium open` | tous | un lien cliquable vers la boutique |
| `/premium top` | tous | les dix plus gros soldes |
| `/premium cosmetics` | tous | la liste des cosmétiques web historiques |
| `/premium give\|take\|set <player> <amount>` | 2 | ajuster un solde |
| `/premium balance <player>` | 2 | lire le solde de quelqu'un d'autre |
| `/store link <code>` | tous | lier ton compte à une session web |
| `/cosmetics` | tous | le menu coffre : parcourir et équiper ce que tu possèdes |
| `/cosmetics list\|equip\|unequip` | tous | la même chose, en commandes texte |
| `/cosmetics grant\|grantall\|revoke` | 2 | distribuer la possession, pour tester |
| `/mount` · `/monture` | tous | invoquer ou renvoyer ta monture équipée |

Aucune commande ne crée un produit, ne liste une commande d'achat ni ne touche à une
clé Stripe. Tout cela passe uniquement par le dashboard. Détails dans
[commandes](docs/commands.fr.md).

## Dashboard

S'enregistre sous le préfixe `store`, avec deux surfaces distinctes :

- **`/api/v1/store/*`** — authentifiée. Produits avec envoi d'image, une galerie, les
  commandes d'achat et la relivraison manuelle, les portefeuilles, les deux catalogues
  de cosmétiques, les statistiques de la boutique et la configuration Stripe.
- **`/store/api/*`** — publique, non authentifiée par nécessité : les acheteurs et les
  serveurs de webhook de Stripe doivent tous les deux l'atteindre. Elle se défend
  autrement — limitation de débit par IP, vérification de même origine sur tout ce qui
  dépense, et vérification de signature HMAC sur le webhook.

> ⚠️ **La frontière des rôles de la surface d'administration est celle de CheeseDash,
> pas celle de CheeseStore.** `StoreApi.handle` n'appelle jamais
> `WebDashboard.requireWrite` ni `requireAdmin` ; le répartiteur de modules du cœur le
> fait pour toute requête non-`GET`, donc les écritures exigent bien `OPERATOR` et une
> session `READ_ONLY` est réellement en lecture seule. La conséquence est le plafond :
> aucune route d'ici ne peut exiger `SUPER_ADMIN`, donc un `OPERATOR` peut changer les
> prix et les commandes de livraison d'un produit, relivrer une commande, fixer le
> solde de crédits de n'importe quel joueur, et écraser ta clé secrète Stripe et ton
> secret de webhook.
> [Le détail](docs/dashboard.fr.md#ce-que-le-cœur-applique-réellement).
>
> ⚠️ **`GET /store/api/players` est publique.** Servie par `handlePublic`, sans aucune
> authentification, elle renvoie tous les pseudos et UUID connus à quiconque atteint le
> port. Si cette liste est privée pour toi, garde le 44311 derrière un proxy capable de
> la restreindre.

## Limites assumées

- **Testé en mode test, par son auteur, sur un seul serveur.** La boucle de paiement
  fonctionne ; les cas limites autour des remboursements, litiges et rétrofacturations
  ne sont pas traités du tout, parce que le mod enregistre ce qu'il a livré et que rien
  ne réconcilie cela avec Stripe.
- **Une livraison `FAILED` est silencieuse.** Aucune alerte, aucun réessai. Le compteur
  est `failedOrders` dans `GET /api/v1/store/stats`, et c'est tout le mécanisme.
- **L'UUID d'un acheteur par carte est pris sur confiance**, depuis le corps de la
  requête de checkout. Les achats en crédits sont différents : ceux-là résolvent le
  compte depuis une session liée.
- **L'historique des commandes plafonne à 5 000 entrées**, les plus anciennes sont
  jetées. Les registres de Stripe sont les registres de référence.
- **Trois clés de configuration sont mortes** (`googleClientId`,
  `googleClientSecret`, `googleRedirectUri`) et les messages destinés aux joueurs du
  sous-système cosmétique sont du français codé en dur. Les deux figurent dans
  [architecture](docs/architecture.fr.md#faiblesses-connues), avec tout le reste de ce
  que je sais être cassé.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux exploiter une boutique monétisée avec ce mod — dons,
rangs, une vraie boutique en ligne ; tu ne peux pas vendre le logiciel lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
- Failles de sécurité → [SECURITY.md](../SECURITY.md). N'ouvre pas d'issue publique
  pour une vulnérabilité, et ne colle jamais une clé Stripe dans l'une d'elles.
