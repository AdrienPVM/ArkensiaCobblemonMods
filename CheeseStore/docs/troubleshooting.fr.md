[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Les symptômes sont groupés par l'endroit où tu les remarques : démarrage, paiements,
dashboard, crédits et cosmétiques. Les problèmes de paiement viennent en premier, parce
que ce sont ceux qui coûtent de l'argent.

---

## Un acheteur a payé et n'a rien reçu

**Cause.** L'une de quatre, et elles se distinguent. Trouve la commande dans la vue
commandes du dashboard, ou :

```bash
curl -H "Authorization: Bearer <session>" http://localhost:44311/api/v1/store/orders | head -c 400
```

| Ce que tu vois | Ce qui s'est passé |
|---|---|
| la commande, `status: "DELIVERED"` | le cas le plus courant : une commande de livraison a échoué dans la console (faute de frappe, mod absent) — Minecraft avale l'erreur, donc la commande d'achat affiche quand même `DELIVERED` |
| la commande, `status: "FAILED"`, avec un `failReason` | rare : l'appel qui exécute une commande a levé une exception |
| la commande, `status: "PENDING"` | le paiement est arrivé, l'acheteur est hors ligne (ou `playerUUID` n'est pas un UUID valide) |
| **aucune commande du tout** | le paiement n'a jamais atteint le mod, ou le produit a été supprimé / les métadonnées manquaient |

**Remède, pour `DELIVERED` sans rien reçu.** Cherche dans le log serveur, autour de
`Delivered orderId=<id>`, l'erreur du gestionnaire de commandes, corrige les `commands`
du produit, et exécute la commande corrigée à la main pour cet acheteur : le dashboard
refuse de relivrer une commande `DELIVERED`.

**Remède, pour `FAILED`.** Lis `failReason` — c'est le message d'exception de la
commande. Généralement une faute de frappe, ou une commande appartenant à un mod qui
n'est plus installé. Corrige les `commands` du produit pour les futurs acheteurs, puis
relivre celle-ci avec le bouton de livraison du dashboard. Note que les commandes
stockées de la commande d'achat ont été résolues au moment de l'achat : la relivraison
exécute donc les **anciennes** commandes ; si la commande elle-même était fausse,
corrige-la dans le produit, ne supprime rien, et exécute la commande corrigée à la main
pour cet acheteur.

**Remède, pour `PENDING`.** Rien à faire. L'acheteur l'obtient à sa prochaine
connexion, et le log dira `Delivered 1 pending purchase(s) to <nom>`. S'il s'est
reconnecté depuis et que c'est toujours en attente, c'est un bug — vérifie si
`deliverPending` a journalisé un échec pour lui.

**Remède, pour l'absence de commande.** Le webhook n'est jamais arrivé ou a été rejeté.
Ouvre la page de ton endpoint de webhook dans Stripe et regarde les tentatives de
livraison :

- `503 Webhook secret not configured` → `stripeWebhookSecret` est vide. Le mod refuse
  d'agir sans lui, volontairement.
- `400 Invalid signature` → le secret est le mauvais pour cet endpoint. Le secret de
  signature est **propre à chaque endpoint** : le `whsec_…` d'un endpoint de production
  diffère de celui de test. C'est de loin l'échec de lancement en production le plus
  courant.
- connexion refusée ou expiration → ton URL est fausse, ou le port n'est pas atteignable
  depuis Internet.
- aucune tentative du tout → l'endpoint n'est pas abonné à
  `checkout.session.completed`.

Stripe réessaie un webhook en échec pendant jusqu'à trois jours, donc corriger le secret
peut récupérer des commandes qui avaient échoué plus tôt. Consulte le journal des
tentatives de l'endpoint avant de rembourser qui que ce soit.

## Le checkout répond 503

**Cause.** Lis le corps de la réponse ; il nomme le problème.

| Corps | Cause |
|---|---|
| `Stripe not configured` | `stripeSecretKey` est vide, donc aucun `StripeClient` n'existe |
| `Store base URL is not configured…` | `storeBaseUrl` est vide **ou contient `localhost`** |

**Remède.** Renseigne la clé, ou renseigne une vraie origine publique sans slash final.
Le rejet de `localhost` est délibéré : Stripe redirigerait l'acheteur vers sa propre
machine et il n'atterrirait nulle part après avoir payé. Le contrôle est un test de
sous-chaîne, donc un vrai nom d'hôte contenant le mot `localhost` est rejeté aussi.

La même condition est journalisée à chaque démarrage :

```text
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
```

## Le checkout répond 403 forbidden_origin

**Cause.** `checkSameOriginPost` a trouvé un `Origin` ou un `Referer` qui ne
correspond pas à l'en-tête `Host` de la requête. Presque toujours un reverse proxy qui
réécrit `Host` sans réécrire le reste, ou une boutique servie depuis un domaine
différent de celui par lequel l'API est atteinte.

**Remède.** Fais passer `Host` inchangé par le proxy, pour que ce que le navigateur a
envoyé comme `Origin` corresponde à ce que le mod voit comme `Host`. Si tu appelles la
route avec `curl`, envoie un en-tête `Origin` correspondant à l'hôte. Note l'unique
trou délibéré de la règle : une requête sans **aucun** des deux en-têtes est autorisée,
parce que certains navigateurs très stricts sur la vie privée les retirent sur un POST
de même origine.

## Le checkout répond 502

**Cause.** Stripe lui-même a refusé la création de la session. Le corps porte le message
de Stripe.

**Remède.** Les suspects habituels : une `currency` non prise en charge sur le produit,
un `priceCents` inférieur au montant minimum facturable pour cette devise, ou un `name`
de produit vide — Stripe exige un nom de ligne. Le mod transmet les champs du produit
tels quels, donc le message de Stripe parle de tes données produit.

## Tout a été livré deux fois

**Cause.** Cela ne devrait presque jamais arriver.
`DeliveryManager.createDelivery` refuse une seconde commande avec le même identifiant
de session Stripe, et le journalise :

```text
[CheeseStore] Duplicate delivery attempt for orderId=cs_live_...
```

Voir cette ligne, c'est le système qui fonctionne — c'est le chemin de redirection et le
webhook qui se font la course, ce qui est normal et attendu. Dans de rares cas, le
contrôle peut quand même laisser passer une seconde commande d'achat avec le **même**
`orderId`. C'est un bug de code, pas un acheteur qui a payé deux fois : ne livre à la
main que ce qui manque, et compare avec Stripe.

**Si les biens sont réellement arrivés deux fois**, les deux commandes ont des
identifiants différents, ce qui veut dire deux vraies sessions de checkout. Vérifie la
vue des commandes : s'il y a deux `orderId` distincts, ton acheteur a payé deux fois, et
c'est un remboursement, pas un bug.

Le seul vrai chemin de double octroi est `POST /store/api/cosmetics/buy`, qui n'a aucun
garde `already_owned` par conception — les cosmétiques historiques sont des achats
répétables. En acheter un deux fois débite deux fois et exécute `applyCommands` deux
fois. Si un cosmétique historique doit être unique, impose-le dans ses commandes.

## Tous les soldes de crédits sont soudain à zéro

**Cause.** `cheesestore_wallets.json` n'a pas pu être analysé. Chaque chargeur de ce
mod attrape sa propre erreur d'analyse et dégrade en vide plutôt que de faire échouer le
démarrage : un fichier de portefeuilles tronqué ou corrompu se lit donc comme *aucun
portefeuille*. Une seule ligne dans le log :

```text
[CheeseStore] Failed to load wallets: <raison>
```

Les écritures sont des réécritures du fichier entier sans fichier temporaire suivi d'un
renommage, donc un crash pendant une sauvegarde peut le tronquer. Le fichier est aussi
écrit sur un thread du pool `CompletableFuture`, ce qui veut dire qu'une interruption
brutale dans les millisecondes qui suivent un changement de solde peut perdre ce
changement.

**Remède.** Arrête le serveur, restaure le fichier depuis une sauvegarde, redémarre. Si
tu n'as pas de sauvegarde, les soldes sont perdus — la seule reconstruction partielle est
le fichier des commandes, qui enregistre ce qui a été vendu mais pas ce qui a été
dépensé depuis.

Sauvegarde ces fichiers. Ce sont le seul registre des crédits qui existe.

---

## Le mod ne se charge pas

**Cause.** Une dépendance manquante. CheeseStore déclare `cheesedash` et `cheeseitems`
comme exigences dures, plus `fabric-api`, Fabric Loader ≥ 0.16.0 et Java 21.

**Remède.** Lis l'erreur Fabric en tête de `logs/latest.log` — elle nomme
l'identifiant manquant. Construis et installe les mods frères
([installation](installation.fr.md#ordre-de-build)). Vérifie aussi que tu n'as pas deux
versions du même jar dans `mods/` ; supprime l'ancien plutôt que de laisser les deux.

## Le build échoue sur les imports `com.cheesedash` ou `com.cheeseitems`

**Cause.** Les dépendances frères se résolvent par un glob `fileTree` sur
`../CheeseDash/build/libs/` et `../CheeseItems/build/libs/`. Ces répertoires sont vides
jusqu'à ce que chaque mod soit construit, et un glob vide n'est pas une erreur — Gradle
compile simplement avec l'import non résolu.

**Remède.** Construis-les d'abord, dans l'ordre :

```bash
cd CheeseDash && ./gradlew build && cd ../CheeseItems && ./gradlew build && cd ../CheeseStore && ./gradlew build
```

Ou lance `tools/build-all.sh` depuis la racine du dépôt, qui parcourt tout l'ensemble
dans l'ordre des dépendances.

## Le module `store` est absent du dashboard

**Cause.** CheeseDash découvre les modules par l'entrypoint `cheesedash` de
`fabric.mod.json`. Si le mod s'est chargé mais que le module est absent, soit CheeseDash
a démarré avant que CheeseStore ne s'enregistre, soit la classe d'entrypoint n'a pas pu
être construite.

**Remède.** Cherche `[CheeseStore] Initialized successfully!` dans le log. Si la ligne
est là et que le module manque toujours, teste la route directement :

```bash
curl http://localhost:44311/store/api/config
```

`503 {"error":"CheeseStore not ready"}` veut dire que le module *est* bien enregistré
mais que `CheeseStoreMod.INSTANCE` est encore null — tu interroges pendant le démarrage,
donc attends `[CheeseStore] Server started` et réessaie. Un `404` veut dire que
CheeseDash n'a jamais enregistré le module ; cherche une exception d'entrypoint dans le
bloc de démarrage de CheeseDash.

## Un changement de configuration n'a eu aucun effet

**Cause.** Cela dépend de la clé, et ce point piège régulièrement.

| Changement | Comportement |
|---|---|
| clés Stripe, nom de boutique, URL de base, `storeEnabled`, `currencyName` | à chaud par `PUT /api/v1/store/config` |
| les mêmes clés éditées **dans le fichier pendant que ça tourne** | écrasées par la prochaine sauvegarde du dashboard |
| les quatre clés de session web | redémarrage requis, et aucune ne fait quoi que ce soit |

**Remède.** Édite par le dashboard, ou arrête le serveur avant d'éditer le fichier.
`CheeseStoreConfig` lit le fichier une fois à `onInitialize` et traite sa copie en
mémoire comme faisant foi — et il réécrit le fichier entier, produits compris, à chaque
sauvegarde.

Si tu as édité `googleClientId`, `googleClientSecret` ou `googleRedirectUri` et que
rien n'a changé : ce sont de la configuration morte. Rien ne les lit. Voir
[configuration](configuration.fr.md#clés-de-session-web).

## Une commande est refusée, ou ne se complète pas

**Cause.** Les branches d'administration de `/premium` et `/cosmetics` exigent le
**niveau de permission 2**. En dessous, Brigadier masque la branche de la complétion et
tu reçois l'erreur vanilla *Unknown or incomplete command*.

**Remède.** Passe le joueur op, ou élève son niveau. Les branches protégées sont
`/premium give|take|set|balance` et `/cosmetics grant|grantall|revoke`. Tout le reste —
`/premium`, `/balance`, `/premium open|top|cosmetics`, `/store link`, `/cosmetics`,
`/cosmetics list|equip|unequip`, `/mount` — est ouvert à n'importe quel joueur.

`Cette commande doit être exécutée par un joueur.` veut dire que tu as lancé une
commande réservée aux joueurs depuis la console. `/premium give` fonctionne depuis la
console ; `/premium` seul, non.

## `Joueur introuvable : <nom>` pour un joueur qui existe

**Cause.** `/premium` résout un pseudo contre les joueurs connectés d'abord, puis contre
le cache utilisateur du serveur. Un joueur qui n'a jamais rejoint ce serveur n'est dans
ni l'un ni l'autre.

**Remède.** Fais-le se connecter une fois. Note que la complétion par tabulation ne
propose que les pseudos **connectés**, mais un pseudo hors ligne tapé en entier se
résout très bien via le cache — une liste de complétion courte ne veut pas dire que le
pseudo sera refusé.

---

## Les messages des cosmétiques sont en français sur un serveur anglophone

**Cause.** Le sous-système cosmétique — `CosmeticsCommands`, `MountCommand`,
`MountManager` — envoie ses retours via `Text.literal` avec des chaînes françaises codées
en dur au lieu de clés de traduction. Les 99 titres de catalogue semés dans
`DefaultCatalog` sont des littéraux français aussi. Le reste du mod utilise
`Text.translatable` avec de vrais fichiers `en_us.json` et `fr_fr.json`.

**Remède.** Réécris les titres du catalogue via le dashboard — ce sont des données. Les
retours des commandes ne sont pas corrigeables sans modifier le Java ; c'est consigné
comme faiblesse connue dans
[architecture](architecture.fr.md#faiblesses-connues).

## Les capes sont achetées et équipées mais personne ne les voit

**Cause.** C'est le fonctionnement prévu. Une cape est rendue **côté client** par un mod
compagnon, `factionwarhud`, qui n'est pas dans ce dépôt. Le serveur ne fait que diffuser
la carte `uuid → capeId` sur le payload `cheesestore:capes` ; la texture est attendue à
`factionwarhud:textures/entity/cape/<capeId>.png`.

**Remède.** Distribue ce mod client, ou désactive la catégorie cape. Les 32 entrées de
cape semées sont activées et tarifées d'emblée, donc sur un serveur à clients vanilla, tu
vends quelque chose d'invisible jusqu'à ce que tu les désactives.

## La monture d'un joueur refuse de s'invoquer

**Cause.** Le plus probablement le temps de recharge de combat. `MountManager` rappelle
une monture quand son propriétaire subit ou inflige des dégâts, et verrouille la
réinvocation pendant `COOLDOWN_TICKS` = 200 ticks, environ 10 secondes. Le message de
refus indique les secondes restantes.

Deux détecteurs déclenchent le rappel : l'événement `AFTER_DAMAGE` et un sondage de
`hurtTime` à chaque tick. Le sondage est un filet de sécurité délibéré, ce qui veut dire
qu'une source de dégâts inhabituelle qui contourne l'événement rappelle quand même la
monture.

Sinon : rien d'équipé dans la catégorie `MOUNT` (équiper ne fait que sélectionner, cela
n'invoque jamais — utilise `/mount`), le joueur est mort ou en spectateur, ou le param
`mob` nomme une entité qui n'est pas chevauchable.

**Remède.** Attends la fin du temps de recharge. Vérifie que `/cosmetics` montre bien
quelque chose d'équipé sous *Montures*. Vérifie que le param `mob` de l'entrée est l'un
des identifiants chevauchables — `horse`, `donkey`, `mule`, `camel`,
`skeleton_horse` sont ceux que `/playcosmetics/enums` propose.

## Une traînée ou un effet de kill ne fait rien

**Cause.** Deux causes différentes pour les deux.

Une **traînée** rend sur `END_SERVER_TICK`, tous les 2 ticks. Si elle apparaît une fois
puis plus jamais, sa `particle` est `minecraft:flame`, absente, inconnue ou paramétrée :
`TrailManager` met en cache la sentinelle d'échec sous la forme de `FLAME` lui-même, donc
une traînée de flammes et une recherche ratée sont identiques dans le cache, et toutes
deux cessent de s'afficher après la première image (`trail_flame` du catalogue par
défaut est concerné ; un avertissement est journalisé une fois pour un mauvais
identifiant).

Un **effet de kill** n'est pas branché par ce mod du tout. Il est piloté par
`CosmeticCombatBridge`, qu'un mod séparé (`factionwar`) recherche **par le nom de la
classe** et invoque par réflexion. Sans ce mod installé, les effets de kill ne se
déclenchent jamais. Si la classe était renommée, ils cesseraient de se déclencher en
silence — la recherche par réflexion échouerait, tout simplement. Un **effet de mort**
n'en dépend pas : `CombatEffectsManager` écoute aussi `AFTER_DEATH`, donc si un effet de
mort ne fait rien, vérifie qu'il est équipé et activé, et que son `effect` fait partie
des six types connus.

**Remède.** Pour une traînée : règle le param `particle` sur une autre particule simple
de la liste `particles` renvoyée par `GET /api/v1/store/playcosmetics/enums` — pas
`minecraft:flame` (ni une particule paramétrée comme `minecraft:dust` ou
`minecraft:falling_dust`) — puis redémarre pour vider le cache. Pour les effets de combat :
confirme que `factionwar` est installé (pour les effets de kill). C'est une observation sur la façon dont les deux
mods sont câblés, lue dans les commentaires de ce mod-ci ; je ne l'ai pas vérifiée contre
`factionwar` lui-même.

## Un achat en crédits échoue sur le site

**Cause.** Lis le code de statut ; chacun est une cause distincte.

| Statut | Corps | Cause |
|---|---|---|
| `401` | `not_authenticated` | aucun cookie `store_session`, **ou** la session de navigateur n'a aucun `/store link` derrière elle |
| `403` | `not_linked` | pratiquement inatteignable : la liaison a été retirée pendant la requête |
| `403` | `forbidden_origin` | `Origin`/`Referer` ne correspondait pas à `Host` |
| `429` | `too_many_requests` | 10 achats par 60 s par IP, partagés entre les trois routes d'achat |
| `402` | `insufficient_credits` | le corps porte `currentBalance` et `required` |
| `409` | `already_owned` | morphos et cosmétiques jouables uniquement ; rien n'a été débité |
| `503` | `server_not_ready` | le serveur Minecraft est arrêté ; rien n'a été débité |

**Remède, pour le `429` en particulier.** Si tous les visiteurs partagent la limite, ton
reverse proxy n'est pas sur une adresse à laquelle le mod fait confiance.
`publicClientIp` n'honore `X-Forwarded-For` que si le pair immédiat est en loopback ou
en RFC1918 (`10.`, `192.168.`, `172.16`–`172.31`) ; depuis toute autre adresse, chaque
requête est comptée contre l'unique IP du proxy. Soit déplace le proxy sur une adresse
privée, soit édite `isTrustedProxy` dans `StoreApi.java`.

## Un OPERATOR a changé les clés Stripe, et je voulais que ça exige SUPER_ADMIN

**Cause.** Pas un bug, et rien que tu puisses régler par configuration.
`StoreApi.handle` n'appelle jamais `WebDashboard.requireWrite` ni `requireAdmin` ;
c'est le répartiteur de modules de CheeseDash qui applique le contrôle à la place, et
il ne connaît qu'une seule barre — `OPERATOR` pour tout non-`GET`. `OPERATOR` est donc
le plafond pour tout sur ce module : produits, commandes d'achat, soldes de
portefeuille et clés Stripe. Un compte `READ_ONLY` ne peut écrire aucun d'entre eux ;
un `OPERATOR` peut tous les écrire.

**Remède.** Ne donne `OPERATOR` qu'à des personnes à qui tu confierais ta clé secrète
Stripe, et `READ_ONLY` à tous ceux qui ont seulement besoin de regarder. Si une route
doit réellement exiger `SUPER_ADMIN`, cela doit être ajouté dans le gestionnaire avec
`requireAdmin` — le répartiteur ne sait pas l'exprimer. Description complète dans
[l'API dashboard](dashboard.fr.md#ce-que-le-cœur-applique-réellement).

---

## Signaler un bug

Ouvre une issue sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **Les lignes `[CheeseStore]` de `logs/latest.log`.** Commence à
   `[CheeseStore] Initializing...` et inclus tout jusqu'à l'échec. Si le serveur a
   planté, toute la trace d'exception.
2. **Les versions des mods** — CheeseStore, CheeseDash, CheeseItems, Fabric Loader,
   Fabric API.
3. **La requête et la réponse HTTP exactes** s'il s'agit d'un problème de route :
   méthode, chemin, statut, corps. Utilise `curl -i`.
4. **Sur quel chemin tu étais** pour un problème de paiement : la redirection
   (`/store/api/session-status`) ou le webhook (`/store/api/webhook`). Le journal des
   tentatives de webhook de Stripe vaut une capture d'écran.
5. **Le `status` et le `failReason` de la commande** pour un problème de livraison,
   depuis `GET /api/v1/store/orders`.

> ⚠️ **Censure avant de publier.** `config/cheesestore.json` contient des secrets
> vivants, et certaines lignes de log aussi. Ne colle jamais `stripeSecretKey`,
> `stripeWebhookSecret` ni un en-tête `Authorization: Bearer` brut dans une issue
> publique. Une clé `sk_live_` dans une issue GitHub, c'est ton compte Stripe
> compromis ; fais-la tourner immédiatement si cela arrive. Un problème de
> **sécurité** va dans [SECURITY.md](../../SECURITY.md), pas dans une issue publique.

Les questions qui ne sont pas des bugs → [Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CheeseStore](../README.fr.md) →
