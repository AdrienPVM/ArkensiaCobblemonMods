[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Un symptôme par section, avec sa cause et son remède. Le log du serveur est le premier
endroit à regarder pour tous : CheeseDash préfixe chaque ligne par `[CheeseDash]`, donc
`grep CheeseDash logs/latest.log` te donne toute l'histoire d'un démarrage en une
quinzaine de lignes.

---

## Le mod ne semble pas charger du tout

Aucune ligne `[CheeseDash]` dans le log.

**Cause.** Fabric n'a jamais chargé le jar : il est dans le mauvais dossier, c'est le
`-sources.jar`, Fabric API est absent, ou le loader est plus ancien que 0.15.11.

**Remède.** Vérifie dans cet ordre :

- Le jar est dans `mods/` et s'appelle `cheesedash-<version>+1.21.1.jar`, pas
  `…-sources.jar`.
- Fabric API est aussi dans `mods/`. Sans lui, Fabric refuse de démarrer et nomme la
  dépendance manquante dans le log de crash : lis donc le haut du log plutôt que le bas.
- `GET /api/v1/server/mods` — ou la bannière de démarrage de Fabric — liste
  `cheesedash`.
- Java est en 21 ou plus : `java -version`. Minecraft 1.21.1 lui-même exige Java 21, et
  le `fabric.mod.json` de CheeseDash déclare `"java": ">=21"` : Fabric refuse donc de
  démarrer sur une version plus ancienne et nomme l'exigence.

---

## Le dashboard ne démarre jamais et le log dit `Address already in use`

```text
[CheeseDash] ❌ Failed to start: Address already in use
```

**Cause.** Quelque chose occupe déjà le port 44311. Sur un hôte mutualisé, c'est souvent
une autre de tes instances de serveur, ou un service du panneau. Cela arrive aussi après
un crash, si l'ancienne JVM n'a pas encore totalement libéré la socket.

**Remède.**

```bash
# Linux
ss -tlnp | grep 44311
# Windows
netstat -ano | findstr 44311
```

Soit tu arrêtes l'autre processus, soit tu déplaces CheeseDash : mets `"port": 44411`
dans `config/cheesedash.json` et redémarre. Rappelle-toi que le WebSocket suit sur
`port + 1` : vérifie donc que **les deux** ports sont libres — 44411 et 44412 dans cet
exemple.

Un échec de bind du port HTTP n'est pas fatal : le serveur continue de tourner sans
dashboard. Un échec de bind du port **WebSocket** est encore plus discret — le HTTP
fonctionne, l'interface charge, et les vues en direct ne se mettent simplement jamais à
jour.

---

## Le dashboard démarre mais le navigateur n'affiche rien depuis une autre machine

Fonctionne sur `http://localhost:44311` sur le serveur, délai dépassé depuis ton poste.

**Cause.** Le port n'est pas joignable. Chez un hébergeur loué, il est bloqué par défaut ;
sur un serveur à la maison, le routeur ne le redirige pas.

**Remède.** N'ouvre **pas** le port. Tunnelise-le :

```bash
ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@ton-serveur
```

puis navigue sur `http://localhost:44311` depuis ta propre machine. Transfère les deux
ports, sinon le graphique de TPS et la liste des joueurs restent vides. Les raisons de
préférer ça à un trou dans le pare-feu sont dans
[exposition réseau](auth-and-roles.fr.md#exposition-réseau).

Si tu tunnelises déjà et que tu n'obtiens toujours rien, vérifie que le serveur écoute
bien sur toutes les interfaces et pas seulement sur une interface interne à un conteneur,
et que `[CheeseDash] ✅ Started on port 44311` est réellement dans le log.

---

## La page charge mais reste blanche, ou mal stylée

La carte de connexion manque, ou le dashboard s'affiche en HTML brut.

**Cause.** Soit le frontend n'est pas dans le jar, soit le navigateur ne peut pas
atteindre les CDN d'où la page tire Tailwind, DaisyUI et Alpine.

**Remède.**

- Ouvre la console du navigateur. Des messages `Refused to load …` désignent la CSP ou un
  CDN bloqué ; un `404` sur `assets/app.js` désigne le jar.
- Si les assets renvoient 404, le jar a été construit sans le frontend.
  `processResources` recopie `index.html`, `preview.html` et `assets/` depuis la
  **racine du dossier du mod** — vérifie qu'ils y sont et reconstruis avec
  `./gradlew clean build`.
- La page a besoin d'un accès Internet sortant *depuis le navigateur*, pas depuis le
  serveur. Un poste totalement hors ligne affichera le dashboard sans style.
- Un cache ou un service worker périmé peut servir un ancien `app.js` contre une nouvelle
  API. Fais un rechargement forcé.

---

## Le login renvoie `401 Invalid token`

**Cause.** Le token ne correspond ni au master token ni à aucun token de compte.

**Remède.**

- Lis le token dans `config/cheesedash.json` plutôt que dans l'historique du terminal —
  l'encadré ne tronque rien, mais les terminaux coupent les lignes, et un token copié
  avec son retour à la ligne est une autre chaîne.
- Les tokens sont sensibles à la casse et font 43 caractères. Les espaces au début et à
  la fin sont retirés pour toi ; ceux du milieu, non.
- Si le token d'un compte a été régénéré, l'ancien cesse de fonctionner immédiatement et
  toutes les sessions qu'il avait créées ont été révoquées.
- Si le fichier de config était malformé au dernier démarrage, CheeseDash **a généré
  silencieusement un nouveau master token et écrasé le fichier**, emportant une partie ou
  la totalité des comptes. Cherche dans le log `Failed to read config, regenerating`
  suivi de la bannière `FIRST RUN — Dashboard token generated`. Le token que tu as est
  alors réellement périmé ; utilise celui qui est maintenant dans le fichier. C'est le mode de défaillance le plus vicieux du mod — voir
  [configuration](configuration.fr.md#le-tableau-accounts).

Pour être sûr que le problème vient bien du justificatif, contourne la page :

```bash
curl -i -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"token":"<ton token>"}'
```

`401` signifie que le token est faux. `400 {"error":"Invalid JSON"}` signifie que c'est
ton échappement de guillemets qui est faux, pas ton token.

---

## Les requêtes renvoient soudain `429 Too many requests`

**Cause.** Plus de 20 requêtes avec un mauvais en-tête `Authorization` sont arrivées de
ton IP en 60 secondes. La source habituelle est un script qui détient un identifiant de
session expiré et réessaie en boucle.

**Remède.** Arrête la boucle et attends la fin de la fenêtre — elle se vide 60 secondes
après le premier échec. Puis reconnecte-toi et utilise le nouvel identifiant de session.

Remarque ce que cela ne couvre *pas* : les logins échoués sur `/auth/login` ne sont pas
comptés, donc un `429` signale toujours un mauvais en-tête Bearer quelque part, jamais un
mauvais mot de passe.

---

## Tout renvoie `401` après une journée de fonctionnement

**Cause.** La session a expiré. Les sessions durent 24 heures à partir de leur **dernier
usage**, et elles sont aussi entièrement perdues au redémarrage du serveur.

**Remède.** Reconnecte-toi. Si tu as coché « se souvenir » sur le formulaire de
connexion, le frontend le fait pour toi quand tu recharges la page, avec le token brut
qu'il a gardé dans le `localStorage` ; sinon il affiche le formulaire de connexion. Un
script doit rappeler `/auth/login`. Si un script doit tourner sans surveillance plus
d'une journée, fais-le se reconnecter sur tout `401` plutôt que de mettre un identifiant
de session en cache pour toujours.

---

## Une route renvoie `503 Server not ready`

**Cause.** Le cœur ne répond `503` que tant que sa référence au serveur Minecraft est
nulle. Dans le handler de `SERVER_STARTED`, cette référence est posée
(`CheeseDashMod.java:180`) avant le démarrage du serveur HTTP (ligne 195) : en pratique,
une route du cœur ne répond donc pas `503`. Tant que le jeu démarre, le port est
simplement fermé et tu obtiens une connexion refusée.

**Remède.** Attends et réessaie. Utilise `GET /api/v1/ping` comme sonde de disponibilité
dès que le port répond, avec le token dans l'en-tête `Authorization` comme toute autre
route ; elle ne touche à aucun état du jeu.

Si le port ne s'ouvre jamais après le chargement du monde, c'est que quelque chose a
interrompu l'événement de démarrage. Cherche une exception d'un autre mod après
`[CheeseDash] Server started — notifying …` et avant `[CheeseDash] ✅ Started on port` :
un module dont `onServerStart` lève une exception interrompt le handler avant le
démarrage du serveur HTTP.

Le `503` propre à un module — `{"error":"CheeseKart not ready"}` et ses semblables —
signifie que ce module n'a pas reçu sa référence au serveur, ce qui est la même cause un
niveau plus bas.

---

## Un module est installé mais absent de la barre latérale

`GET /api/v1/modules` ne le liste pas.

**Cause.** L'une de quatre choses, par ordre de probabilité :

1. Le mod ne déclare pas l'entrypoint `cheesedash` dans son `fabric.mod.json`.
2. Son préfixe entre en collision avec celui d'un autre module. Le log le dit :
   `Duplicate module prefix 'x' — skipping …`.
3. Son préfixe entre en collision avec une route du **cœur** — `ping`, `status`,
   `modules`, `auth`, `admin`, `dashboard`, `server`, `webhooks`. Le module s'enregistre
   correctement et apparaît dans `/modules`, mais aucun des chemins que le cœur traite
   lui-même ne l'atteint (et rien du tout sous `admin/`), et rien ne t'en avertit.
4. Le mod déclare CheeseDash en `suggests` et quelque chose a mal tourné dans la
   résolution de la classe d'entrypoint, que Fabric a donc ignorée silencieusement.

**Remède.** Cherche dans le log les deux lignes qui prouvent l'enregistrement :

```text
[CheeseDash] Module registered: CheeseKart (prefix: /api/v1/kart)
[CheeseDash] Module loaded: CheeseKart (kart)
```

Aucune ligne signifie que l'entrypoint manque ou que le mod n'a pas chargé — consulte la
page de dépannage de cet autre mod. Les lignes présentes mais la page toujours absente
signifie que le frontend n'a pas de vue pour ce préfixe, ce qui est un problème de
frontend et non d'enregistrement.

---

## Une modification de `config/cheesedash.json` reste sans effet

**Cause.** Le fichier est lu une fois, à l'initialisation du mod, et réécrit depuis la
mémoire dès qu'un compte ou une préférence change. L'éditer sur un serveur en
fonctionnement est à la fois ignoré et condamné.

**Remède.** Arrête le serveur, édite, démarre. Ou fais le changement par l'API :
`PUT /dashboard/admin-player`, `POST /admin/accounts`, et ainsi de suite. `port` et
`enabled` n'ont pas d'API et exigent toujours un redémarrage.

⚠️ Valide ton JSON avant de redémarrer. Une erreur de syntaxe, ou une valeur de `role`
inconnue, fait générer un nouveau master token à CheeseDash et réécrire le fichier, en
perdant des comptes au passage (tous, pour une erreur de syntaxe). Garde une sauvegarde.

---

## Une requête est refusée avec `403 Insufficient permissions`

```json
{"error":"Insufficient permissions — requires OPERATOR"}
```

**Cause.** Le rôle du compte est inférieur à ce que la route exige. Le message nomme le
rôle requis.

**Remède.** Soit tu utilises un compte qui a ce rôle, soit tu élèves celui-ci :

```bash
curl -X PUT http://localhost:44311/api/v1/admin/accounts/moderator \
  -H "Authorization: Bearer <une session SUPER_ADMIN>" \
  -H "Content-Type: application/json" -d '{"role":"SUPER_ADMIN"}'
```

⚠️ Le changement ne s'applique **pas** aux sessions déjà existantes, parce qu'une session
capture son rôle à sa création. Révoque la session de l'utilisateur avec
`DELETE /admin/sessions/{id}` et fais-le se reconnecter, ou attends 24 heures.

La matrice complète de ce que chaque rôle peut atteindre est dans
[authentification et rôles](auth-and-roles.fr.md#ce-que-peut-faire-chaque-rôle).

---

## Le TPS en direct, la liste des joueurs et le flux de logs ne se mettent jamais à jour

Le REST fonctionne, tout le temps réel est figé.

**Cause.** Le WebSocket n'est pas connecté. Trois raisons, dans cet ordre :

1. Le port 44312 n'est pas joignable — le cas courant quand tu n'as tunnelisé ou relayé
   que le 44311.
2. Le client envoie autre chose que le master token. Le WebSocket n'accepte que le master
   token et rien d'autre — et le dashboard fourni envoie toujours son identifiant de
   session, même quand tu t'es connecté avec le master token, si bien que sa propre
   socket est refusée pour tous les comptes. Voir
   [la note à ce sujet](auth-and-roles.fr.md#le-websocket-naccepte-que-le-master-token).
3. Le serveur WebSocket a échoué à son bind. Le HTTP continue de fonctionner, donc rien
   d'évident ne casse — cherche `[CheeseDash] WebSocket started on port 44312` dans le
   log, et son absence.

**Remède.** Transfère ou relaie aussi `port + 1`. Un proxy doit également laisser passer
les en-têtes `Upgrade` et `Connection`, sinon la poignée de main échoue sur un `200` au
lieu d'un `101`. Pour les pages fournies, la cause 2 n'a pas de remède de configuration :
elles retombent sur un sondage de `GET /api/v1/status` toutes les deux secondes. Les
données en direct exigent un client à toi qui envoie le master token.

Une connexion qui s'authentifie mais n'affiche rien est un autre problème : le client ne
s'est abonné à aucun canal. La console du navigateur montre le message `subscribe` que la
page a envoyé.

---

## Un redémarrage planifié a arrêté le serveur au lieu de le relancer

**Cause.** `ServerScheduler` sauvegarde les mondes, lance une JVM neuve avec les mêmes
arguments, puis arrête le serveur courant. Pour la lancer, il doit trouver le jar du
serveur : d'abord en cherchant `-jar` dans les arguments du processus courant, puis en
balayant le répertoire de travail à la recherche de `fabric-server-launch*.jar` ou
`server*.jar`. Sur un hôte qui démarre via un script d'enrobage, un lanceur shadé ou un
jar au nom inhabituel, aucune des deux recherches n'aboutit et il lève
`Cannot find server jar for restart`. L'exception est seulement journalisée
(`Failed to spawn restart process: …`), et l'arrêt a lieu quand même.

**Remède.** Teste un seul `RESTART` sur tout nouvel hôte avant de lui confier une
planification récurrente. Si cela échoue, laisse le redémarrage automatique de ton
panneau gérer la relance et utilise `STOP` au lieu de `RESTART`, qui est honnête sur ce
qu'il fait. Cherche `[CheeseDash] Restart process spawned: …` dans le log — si cette
ligne est là, la relance a bien été tentée.

---

## Les notifications Discord n'arrivent pas

**Cause.** Le plus souvent, l'ensemble `events` du webhook est vide. Un webhook créé par
`POST /webhooks` démarre activé et abonné à **rien**.

**Remède.**

1. Lance d'abord `POST /api/v1/webhooks/{id}/test`. Il rapporte la livraison, pas la
   configuration : `{"success":true}` prouve que l'URL fonctionne.
2. Si le test réussit mais que les vrais événements n'arrivent jamais, envoie un tableau
   `events` au webhook par `PUT` avec les identifiants voulus.
   `GET /webhooks/event-types` liste les 23.
3. Vérifie que `enabled` vaut `true`.
4. Chaque webhook a sa propre limitation de débit sortante : tout événement qui arrive
   moins de deux secondes après le précédent envoi vers le même webhook, quel que soit son
   type, est écarté plutôt que mis en file.

Détails dans [webhooks Discord](discord-webhooks.fr.md).

---

## Le journal d'audit a perdu ses anciennes entrées

**Cause.** Il est plafonné à 10 000 entrées et tronqué par le bout le plus ancien, en
mémoire comme sur disque.

**Remède.** Fais tourner `config/cheesedash_audit.json` toi-même — copie-le ailleurs
périodiquement — si tu as besoin d'un historique plus long. Il n'existe aucun réglage
pour ce plafond.

---

## La compilation échoue avec `package com.sun.net.httpserver is not visible`

**Cause.** Le serveur HTTP du JDK vit dans le module `jdk.httpserver`. Sous **JDK 21, il
est exporté** : un build Gradle normal n'a donc besoin d'aucune option — le
`build.gradle` de CheeseDash ne pose rien de plus que `options.release = 21`, et il
compile. L'erreur signifie que le compilateur n'est pas dans cette situation :

- un réglage `release`, `sourceCompatibility` ou de toolchain inférieur à 11, époque où
  `com.sun.net.httpserver` n'était pas un package exporté ;
- un IDE ou un build configuré pour compiler contre un **graphe de modules restreint**,
  où `jdk.httpserver` n'est pas dans `--add-modules` ;
- ou du code qui cherche `sun.net.httpserver` — le package d'implémentation interne,
  écrit sans le `com.` — qui, lui, n'est réellement pas exporté et ne le sera jamais.

**Remède.** Assure-toi d'abord que le compilateur cible bien 21 :

```groovy
tasks.withType(JavaCompile).configureEach {
    it.options.release = 21
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

C'est ce que fait CheeseDash, et cela suffit. Si ton build restreint réellement le graphe
de modules, ajoute le module plutôt que de forcer un export :

```groovy
tasks.withType(JavaCompile).configureEach {
    it.options.compilerArgs += ['--add-modules', 'jdk.httpserver']
}
```

Un `--add-exports` d'un package interne est le marteau-pilon, et n'est correct que pour
le cas `sun.net.httpserver` :

```groovy
it.options.compilerArgs += ['--add-exports', 'jdk.httpserver/sun.net.httpserver=ALL-UNNAMED']
```

Si tu en viens là, vérifie d'abord que tu n'as pas simplement fait une faute de frappe
dans l'import.

---

## La compilation d'un module échoue sur des classes `com.cheesedash` manquantes

**Cause.** CheeseDash n'a pas été construit : le glob `fileTree` du `build.gradle` de ton
module n'a donc rien trouvé et la dépendance s'est résolue en un ensemble vide.

**Remède.**

```bash
cd CheeseDash && ./gradlew build
cd ../TonMod  && ./gradlew build
```

Ou lance `tools/build-all.sh` depuis la racine du dépôt, qui parcourt les mods dans
l'ordre des dépendances. Vérifie que `CheeseDash/build/libs/cheesedash-*.jar` existe et
qu'il ne s'agit pas seulement du `-sources`.

---

## Signaler un bug

Ce qui rend un rapport exploitable, par ordre d'utilité :

1. **`logs/latest.log`**, ou au minimum toutes les lignes contenant `CheeseDash` du
   démarrage jusqu'à la panne. La séquence de démarrage nous dit la version, la liste des
   modules, quels ports se sont liés, et si la config s'est chargée proprement.
2. **La requête exacte** : méthode, chemin, corps, puis le statut et le corps de la
   réponse. Une commande `curl` qui reproduit le problème vaut mieux qu'une description
   de bouton.
3. **La console du navigateur et l'onglet Réseau** pour tout ce qui ressemble à un
   problème de frontend. Une ligne `Refused to load` ou une requête en rouge est
   généralement toute la réponse.
4. **Quels mods sont installés**, via `GET /api/v1/server/mods`. Beaucoup de symptômes se
   révèlent appartenir à un module plutôt qu'à CheeseDash.
5. **Ton `config/cheesedash.json` — champ `token` et tokens de comptes retirés.**
   ⚠️ Ce sont des mots de passe. Remplace-les par `"REDACTED"` avant de coller quoi que
   ce soit.

Dépose le rapport sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues). Pour tout ce
qui ressemble à une vulnérabilité, lis d'abord [SECURITY.md](../../SECURITY.md) et n'ouvre
pas d'issue publique.

---

← [Architecture](architecture.fr.md) · [CheeseDash](../README.fr.md) →
