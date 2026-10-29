[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](writing-a-module.md)

# Écrire un module

Un module CheeseDash est un mod Fabric qui implémente une interface et la déclare comme
entrypoint. CheeseDash le découvre au démarrage, lui donne un espace de noms sous
`/api/v1/<prefix>/`, le place dans la barre latérale, et lui route toutes les requêtes
sous ce préfixe. L'authentification, la gestion des sessions, le filtrage par rôle sur
les écritures, le journal d'audit, les en-têtes de sécurité et la politique CORS sont
déjà faits quand ton code s'exécute.

Quinze mods de ce dépôt font exactement cela.
[`CheeseKart/src/main/java/com/cheesekart/dashboard/`](../../CheeseKart/src/main/java/com/cheesekart/dashboard/)
est le plus court des vrais exemples — une classe de module de 38 lignes et neuf
routes — et mérite d'être ouvert à côté de cette page.

---

## Les trois pièces

```text
build.gradle          compiler contre le jar construit de CheeseDash
fabric.mod.json       déclarer l'entrypoint "cheesedash" et la dépendance dure
YourDashboardModule   implémenter DashboardModule
```

### 1. Dépendre de CheeseDash dans `build.gradle`

CheeseDash n'est pas publié sur un dépôt Maven. Les mods frères de ce dépôt compilent
contre son **jar construit**, résolu par un glob pour que le numéro de version ne soit
pas figé :

```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    // CheeseDash — à construire d'abord
    modImplementation files(fileTree(
        dir: "../CheeseDash/build/libs",
        includes: ["cheesedash-*.jar"],
        excludes: ["*-sources.jar", "*-dev.jar"]))

    implementation 'com.google.code.gson:gson:2.10.1'
}
```

La conséquence à retenir : **CheeseDash doit être construit avant ton mod**, sinon le
glob ne correspond à rien et tu obtiens une erreur de compilation sur des classes
`com.cheesedash` manquantes. Depuis la racine du dépôt, `tools/build-all.sh` parcourt les
mods dans l'ordre des dépendances.

`com.sun.net.httpserver` n'a besoin d'aucune option de compilation sous JDK 21 : le
module `jdk.httpserver` l'exporte. Si tu rencontres
`package com.sun.net.httpserver is not visible`, la cause est presque toujours un réglage
de toolchain resté à l'époque de `--release 8` plutôt qu'un export manquant ;
[dépannage](troubleshooting.fr.md#la-compilation-échoue-avec-package-comsunnethttpserver-is-not-visible)
le détaille.

### 2. Déclarer l'entrypoint

```json
{
  "schemaVersion": 1,
  "id": "mymod",
  "entrypoints": {
    "main": ["com.example.mymod.MyMod"],
    "cheesedash": ["com.example.mymod.dashboard.MyDashboardModule"]
  },
  "depends": {
    "fabricloader": ">=0.15.11",
    "minecraft": "~1.21.1",
    "java": ">=21",
    "fabric-api": "*",
    "cheesedash": "*"
  }
}
```

`cheesedash` est un **entrypoint personnalisé**, inconnu de Fabric. C'est CheeseDash qui
le collecte lui-même :

```java
FabricLoader.getInstance().getEntrypoints("cheesedash", DashboardModule.class)
```

Deux choix délibérés s'offrent à toi ici :

- **`depends`** signifie que ton mod refuse de charger sans CheeseDash. Correct pour un
  mod dont tout l'intérêt est le dashboard.
- **`suggests`** signifie qu'il charge dans les deux cas, et que ta classe de module
  n'est simplement jamais instanciée — Fabric ne résout pas un entrypoint dont la classe
  dépend d'un mod absent. Correct pour un mod qui fonctionne seul en jeu et ne gagne
  qu'une page d'administration. Attention : tout le reste de ton mod qui importerait une
  classe `com.cheesedash` lèverait alors `NoClassDefFoundError`, donc garde cette surface
  d'import à l'intérieur du package `dashboard`.

### 3. Implémenter `DashboardModule`

Quatre méthodes sont obligatoires, quatre autres ont une implémentation par défaut.

| Méthode | Obligatoire | À quoi elle sert |
|---|---|---|
| `String getPrefix()` | oui | Ton espace de noms. `"kart"` donne `/api/v1/kart/*`. En minuscules, sans slash |
| `String getDisplayName()` | oui | Libellé de la barre latérale, p. ex. `"CheeseKart"` |
| `String getNavIcon()` | oui | Un seul emoji pour la barre latérale |
| `String getNavDescription()` | oui | Une courte ligne sous le libellé |
| `void handleRequest(HttpExchange, String subRoute)` | oui | Toutes les requêtes vers ton préfixe. `subRoute` est le chemin après le préfixe, commençant par `/` |
| `void onServerStart(MinecraftServer)` | défaut vide | Là où tu captures la référence au serveur |
| `void onServerStop()` | défaut vide | Lâche cette référence, arrête tes propres threads |
| `String getPublicPrefix()` | défaut `null` | Renvoie autre chose que `null` pour obtenir en plus un contexte non authentifié ; voir [plus bas](#contextes-publics-non-authentifiés) |
| `void handlePublicRequest(HttpExchange, String path)` | défaut vide | Traite `/{publicPrefix}/api/*` |

> ⚠️ Ne choisis pas un préfixe qui entre en collision avec une route du cœur : `ping`,
> `status`, `modules`, `auth`, `admin`, `dashboard`, `server`, `webhooks`. Les contrôles
> du cœur passent d'abord, ton module n'est jamais atteint, et rien ne t'en avertit. Les
> collisions avec un autre *module*, elles, sont signalées au démarrage et le second
> module est ignoré.

---

## Les helpers que CheeseDash te donne

Tous statiques sur `WebDashboard`.

### Répondre

```java
WebDashboard.respond(exchange, 200, "{\"ok\":true}");        // statut + chaîne JSON brute
WebDashboard.respondJson(exchange, someJsonObject);          // 200 + sérialisation Gson
String body = WebDashboard.readBody(exchange);               // le corps de la requête en UTF-8
```

`respond` pose `Content-Type: application/json; charset=utf-8`, écrit le
`Content-Length` et ferme le flux. Appelle-la **exactement une fois** par exchange : un
second appel lève une exception, et zéro appel laisse la connexion pendante jusqu'au
timeout du client. C'est ce second mode de défaillance qu'il faut surveiller dans une
branche de retour anticipé.

Construis le JSON avec les `JsonObject`/`JsonArray` de Gson plutôt qu'en concaténant des
chaînes — un nom de joueur contenant un guillemet produirait sinon une réponse
malformée. Là où le cœur concatène, il fait d'abord passer la valeur par un helper
`escapeJson`.

Pour une image générée, il existe un spécialiste :

```java
WebDashboard.servePngWithRevalidation(exchange, path);  // ETag + Last-Modified, gère le 304
```

Il répond `404` si le fichier manque, `304` quand le `If-None-Match` ou le
`If-Modified-Since` du client correspond toujours, et sinon les octets avec une fenêtre
de cache de cinq minutes. CobbleCheeseScore l'utilise pour ses images de scoreboard
rendues.

### Permissions

```java
if (!WebDashboard.requireWrite(exchange)) return;   // OPERATOR ou plus, sinon 403
if (!WebDashboard.requireAdmin(exchange)) return;   // SUPER_ADMIN seulement, sinon 403
if (!WebDashboard.requireRole(exchange, AdminAccount.Role.OPERATOR)) return;
boolean ok = WebDashboard.hasRole(exchange, AdminAccount.Role.OPERATOR);  // n'écrit aucune réponse
```

Chaque `require*` écrit elle-même le `403` avant de renvoyer `false`, et c'est pourquoi
le motif est `if (!…) return;` et non un `if/else`.

Rappelle-toi que `DashboardApi` a déjà appliqué `requireWrite` à chaque requête non-`GET`
avant de t'appeler. Rajouter `requireWrite` est inoffensif et rend l'intention
explicite, mais ce n'est pas ce qui te protège. Ce que tu **dois** ajouter toi-même,
c'est `requireAdmin` sur tout ce qu'un `OPERATOR` ne devrait pas toucher : tout
supprimer, réécrire un fichier de configuration, déplacer de l'argent réel.

> Une mise en garde tirée d'un vrai bug de ce dépôt : `KartApi` appelle
> `WebDashboard.requireWrite(exchange)` et **ignore le résultat**, puis continue vers le
> handler. Si la barrière centrale n'existait pas, cela aurait laissé un compte
> `READ_ONLY` écrire, avec le corps du `403` déjà envoyé et une seconde tentative de
> réponse à sa suite. Branche toujours sur la valeur de retour.

### Contexte de session

```java
SessionManager.Session s = WebDashboard.getSession(exchange);
String who  = s.getUsername();      // "admin", ou le nom du compte
String role = s.getRole().name();   // READ_ONLY | OPERATOR | SUPER_ADMIN
String ip   = s.getIp();
```

La session est attachée à l'exchange par le middleware avant le dispatch. Elle est non
nulle pour toute requête parvenue à ton `handleRequest` ; elle **est** nulle dans
`handlePublicRequest`, qui s'exécute entièrement hors authentification.

### Toucher au jeu : la seule règle qui compte

```java
WebDashboard.executeOnServerThread(server, () -> {
    manager.deleteZone(id);                 // s'exécute sur le thread de tick du serveur
});
```

**Toute mutation de l'état Minecraft passe par là.** Ton handler s'exécute sur l'un des
quatre threads d'un pool fixe nommé `CheeseDash-HTTP`, en parallèle de la boucle de jeu.
Faire apparaître une entité, téléporter un joueur, changer un bloc ou lancer une commande
depuis ce thread est une course avec le tick du serveur, et les crashs que cela produit
n'apparaissent que sous charge — autrement dit en production, et jamais en test.

`executeOnServerThread` confie la tâche à `server.execute(...)` et bloque sur un
`CompletableFuture` pendant **au plus 5 secondes**. Trois conséquences :

- Elle lève `IllegalStateException("Server not available")` si tu passes `null`.
  Vérifie ta référence au serveur et réponds `503` à la place.
- Elle propage ce que ta tâche a levé, enveloppé dans une `ExecutionException`.
- Elle lève `TimeoutException` au bout de 5 secondes. Une tâche lente — charger une
  région, réécrire un gros fichier — dépassera le délai alors qu'elle *tourne encore* sur
  le thread serveur. Ton handler répond alors `500` pour une opération qui va réussir une
  seconde plus tard. Pour tout ce qui est lent, n'attends pas : appelle directement
  `server.execute(...)` et réponds dans l'esprit d'un `202`, en documentant que la
  réponse signifie *accepté*, pas *fait*.

Les lectures sont l'autre moitié de la règle. **Lire un objet immuable ou thread-safe
directement depuis le thread HTTP est correct, et c'est ce que fait chaque module** — une
`ConcurrentHashMap` de presets, un record immuable, un objet de config remplacé en bloc
plutôt que muté sur place. Lire l'état de bloc d'un `ServerWorld` vivant ou parcourir
l'inventaire d'un joueur, non : c'est de l'état de jeu, et cela exige le thread serveur
comme n'importe quelle mutation.

### Pousser vers le WebSocket

```java
DashboardRegistry.broadcast("kart", msg.toString());
```

À envoyer et oublier. Le message atteint tous les clients WebSocket abonnés à ce canal,
et ne fait absolument rien si le serveur WebSocket est indisponible — l'appel est donc
sûr depuis un mod qui pourrait tourner sans le WebSocket de CheeseDash.

Le même appel alimente aussi `DiscordWebhookManager.onBroadcast`, qui mappe certains noms
de canaux et types de messages vers des événements Discord. Choisir un nom de canal déjà
connu de ce mapping t'offre la livraison Discord gratuitement ; en choisir un nouveau,
non. Les noms de canaux et les formes de payload sont dans [WebSocket](websocket.fr.md).

### Porte de sortie

```java
HttpServer raw = CheeseDashMod.INSTANCE.getWebDashboard().getHttpServer();
raw.createContext("/my-thing", exchange -> { … });
```

Disponible, et tu ne devrais pas en avoir besoin. Un contexte enregistré ainsi contourne
l'authentification, les en-têtes de sécurité et le journal d'audit. La façon supportée de
servir quelque chose de non authentifié est le préfixe public ci-dessous.

---

## Un module complet

Un module qui suit un compteur par joueur. Une route de lecture, une route d'écriture,
assez pour montrer chaque règle en pratique. Il compile tel quel, contre CheeseDash 1.0.0
et Minecraft 1.21.1.

### `src/main/resources/fabric.mod.json`

```json
{
  "schemaVersion": 1,
  "id": "cheesecount",
  "version": "${version}",
  "name": "CheeseCount",
  "environment": "*",
  "entrypoints": {
    "main": ["com.example.cheesecount.CheeseCountMod"],
    "cheesedash": ["com.example.cheesecount.dashboard.CountDashboardModule"]
  },
  "depends": {
    "fabricloader": ">=0.15.11",
    "minecraft": "~1.21.1",
    "java": ">=21",
    "fabric-api": "*",
    "cheesedash": "*"
  }
}
```

### La classe du module

```java
package com.example.cheesecount.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CountDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()        { return "count"; }
    @Override public String getDisplayName()   { return "CheeseCount"; }
    @Override public String getNavIcon()       { return "🔢"; } // 🔢
    @Override public String getNavDescription(){ return "Compteurs par joueur"; }

    @Override
    public void onServerStart(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onServerStop() {
        this.server = null;
    }

    @Override
    public void handleRequest(HttpExchange exchange, String subRoute) throws IOException {
        // La référence au serveur n'est posée que sur SERVER_STARTED, et le serveur HTTP
        // accepte des connexions à partir de ce même instant. Réponds honnêtement entre
        // les deux.
        if (server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseCount not ready\"}");
            return;
        }
        CountApi.handle(exchange, server, subRoute);
    }
}
```

### La classe d'API

```java
package com.example.cheesecount.dashboard;

import com.cheesedash.AdminAccount;
import com.cheesedash.DashboardRegistry;
import com.cheesedash.SessionManager;
import com.cheesedash.WebDashboard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CountApi {

    // Concurrente, parce que les threads HTTP la lisent pendant que le thread serveur
    // l'écrit.
    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute)
            throws IOException {
        String method = exchange.getRequestMethod();
        String path = subRoute.startsWith("/") ? subRoute : "/" + subRoute;

        if (path.equals("/counts") && method.equals("GET")) {
            listCounts(exchange);
            return;
        }
        if (path.equals("/counts") && method.equals("POST")) {
            // requireWrite a déjà tourné de façon centralisée ; cette route récompense
            // aussi un joueur, donc on la garde à OPERATOR et on branche sur le résultat.
            if (!WebDashboard.requireWrite(exchange)) return;
            addCount(exchange, server);
            return;
        }
        if (path.equals("/counts") && method.equals("DELETE")) {
            // Tout effacer n'est pas le travail d'un OPERATOR.
            if (!WebDashboard.requireAdmin(exchange)) return;
            COUNTS.clear();
            WebDashboard.respond(exchange, 200, "{\"cleared\":true}");
            return;
        }
        WebDashboard.respond(exchange, 404,
            "{\"error\":\"Unknown count route: " + escape(path) + "\"}");
    }

    // ── GET /api/v1/count/counts ─────────────────────────────────────────────
    // Une lecture d'une map thread-safe. Aucun passage de thread nécessaire : ceci
    // s'exécute directement sur le thread HTTP, et c'est tout l'intérêt de garder la map
    // concurrente.
    private static void listCounts(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        COUNTS.forEach((name, n) -> {
            JsonObject o = new JsonObject();
            o.addProperty("player", name);
            o.addProperty("count", n);
            arr.add(o);
        });
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    // ── POST /api/v1/count/counts ────────────────────────────────────────────
    // Corps : {"player":"RoiCheese","amount":1}
    private static void addCount(HttpExchange exchange, MinecraftServer server)
            throws IOException {
        JsonObject body;
        try {
            body = JsonParser.parseString(WebDashboard.readBody(exchange)).getAsJsonObject();
        } catch (Exception e) {
            // Parse de façon défensive. Le cœur contient des handlers qui ne le font pas,
            // et ils transforment la faute de frappe d'un client en 500.
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        // Valide avant de faire quoi que ce soit. Un identifiant ou un nom qui atteint un
        // chemin de fichier ou une recherche de registre doit d'abord être vérifié contre
        // la traversée de répertoires.
        if (!body.has("player")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"player is required\"}");
            return;
        }
        String name = body.get("player").getAsString().trim();
        if (!isValidName(name)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid player name\"}");
            return;
        }
        int amount = body.has("amount") ? body.get("amount").getAsInt() : 1;
        if (amount < 1 || amount > 1000) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"amount must be 1-1000\"}");
            return;
        }

        int updated = COUNTS.merge(name, amount, Integer::sum);

        // Prévenir le joueur est une mutation de l'état du jeu. Cela passe sur le thread
        // serveur, toujours, même si sendMessage a l'air inoffensif.
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
                if (p != null) {
                    p.sendMessage(net.minecraft.text.Text.literal("+" + amount), false);
                }
            });
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }

        // Pousse l'info vers tout onglet du dashboard abonné à ce canal. À envoyer et
        // oublier : l'appel ne fait rien quand aucun serveur WebSocket ne tourne.
        JsonObject ws = new JsonObject();
        ws.addProperty("type", "count_update");
        ws.addProperty("player", name);
        ws.addProperty("count", updated);
        DashboardRegistry.broadcast("count", ws.toString());

        // Qui a fait ça est disponible si tu veux une entrée d'audit plus riche que le
        // "count.post" automatique que le cœur écrit pour chaque requête non-GET.
        SessionManager.Session session = WebDashboard.getSession(exchange);
        String by = session != null ? session.getUsername() : "unknown";

        JsonObject resp = new JsonObject();
        resp.addProperty("player", name);
        resp.addProperty("count", updated);
        resp.addProperty("by", by);
        WebDashboard.respondJson(exchange, resp);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isValidName(String s) {
        return s != null && !s.isEmpty() && s.length() < 64
            && !s.contains("..") && !s.contains("/") && !s.contains("\\")
            && !s.contains("%");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
```

Construis CheeseDash, puis ce mod, puis dépose les deux jars dans `mods/`. Le log devrait
dire :

```text
[CheeseDash] Module registered: CheeseCount (prefix: /api/v1/count)
[CheeseDash] Module loaded: CheeseCount (count)
[CheeseDash] 1 module(s) discovered.
```

Et les routes répondent :

```bash
curl -H "Authorization: Bearer ses_…" http://localhost:44311/api/v1/count/counts
# []

curl -X POST http://localhost:44311/api/v1/count/counts \
  -H "Authorization: Bearer ses_…" -H "Content-Type: application/json" \
  -d '{"player":"RoiCheese","amount":3}'
# {"player":"RoiCheese","count":3,"by":"admin"}
```

Une session `READ_ONLY` sur ce `POST` reçoit
`403 {"error":"Insufficient permissions — requires OPERATOR"}` de la barrière centrale,
sans que ton handler ne s'exécute.

---

## Contextes publics non authentifiés

Renvoyer autre chose que `null` depuis `getPublicPrefix()` fait enregistrer à CheeseDash
un second contexte, en dehors de `/api/` et en dehors de l'authentification :

```java
@Override public String getPublicPrefix() { return "shop"; }

@Override
public void handlePublicRequest(HttpExchange exchange, String path) throws IOException {
    // path est ce qui suivait "/shop/api", p. ex. "/products"
}
```

Ce que CheeseDash fait alors, sur le même port HTTP :

| Requête | Traitement |
|---|---|
| `/shop` | redirection `301` vers `/shop/` |
| `/shop/` ou `/shop` avec un reste vide | sert `index.html` |
| `/shop/api/<reste>` | appelle `handlePublicRequest(exchange, "/<reste>")` |
| tout le reste sous `/shop/` | servi depuis le **classpath** à `/<prefix>/<path>`, avec `index.html` en repli pour les chemins inconnus |

Tes fichiers statiques vont donc dans `src/main/resources/shop/` et sont servis depuis le
jar. Les chemins contenant `..` sont rejetés avec un `403`. Les réponses sont gzippées
quand le type de contenu est compressible et que le client dit l'accepter. Le jeu
d'en-têtes est plus strict que celui du dashboard : HSTS, `Permissions-Policy`,
`frame-ancestors 'none'`, et une CSP qui autorise Stripe — ce mécanisme existe pour la
vitrine de CheeseStore et ses en-têtes ont été écrits pour ce cas.

> ⚠️ **Ici, il n'y a aucune authentification, aucune session, aucun rôle et aucune entrée
> d'audit**, et `WebDashboard.getSession(exchange)` renvoie `null`. Quiconque atteint le
> port atteint ces routes. Tous les contrôles — limitation de débit, vérification de
> signature sur un webhook de paiement, idempotence — sont à ta charge. Ne mets rien
> derrière un préfixe public que tu ne publierais pas sur Internet ouvert.

Une exception sortant de `handlePublicRequest` est attrapée, journalisée et répondue en
`500 {"error":"Internal server error"}`, sans détail divulgué à l'appelant.

---

## Atteindre un module depuis un autre mod

Rien dans CheeseDash n'arbitre les appels entre mods. Le motif utilisé par les mods
Arkensia est la réflexion sur le singleton public de l'autre mod, afin que le mod
appelant charge encore quand l'autre est absent — les hooks de CheeseGuard vers
CheeseKart, CobbleShift et CobblemonDungeons fonctionnent tous ainsi. Cherche la classe
par son nom, mets en cache les méthodes dont tu as besoin, expose un `isAvailable()`, et
prépare-toi à ce que la recherche échoue.

N'utilise jamais `getDeclaredMethod("someYarnName", …)` sur une classe **Minecraft** pour
ça : à l'exécution, ces noms sont intermédiaires, pas Yarn. Parcours
`getDeclaredMethods()` et filtre sur les types de paramètres à la place.

---

## Liste de contrôle avant de livrer un module

- Le préfixe n'entre pas en collision avec une route du cœur ni avec un autre module.
- `handleRequest` répond `503` tant que ta référence au serveur est nulle.
- Chaque branche appelle exactement un `respond*`, branches d'erreur comprises.
- Chaque route non-`GET` qu'un `OPERATOR` ne devrait pas atteindre appelle `requireAdmin`
  et branche sur le résultat.
- Chaque identifiant ou nom qui atteint un chemin de fichier est validé contre `..`, `/`,
  `\` et `%`.
- Chaque mutation de l'état du jeu est à l'intérieur d'`executeOnServerThread`, et tout
  ce qui pourrait dépasser 5 secondes ne l'est pas.
- L'état partagé lu depuis les threads HTTP est concurrent ou immuable.
- Le JSON est construit avec Gson, ou échappé à la main s'il est concaténé.
- `onServerStop` libère la référence au serveur et arrête tout thread que tu as démarré.

---

← [WebSocket](websocket.fr.md) · [Interface web](web-ui.fr.md) →
