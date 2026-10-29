[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](quick-menu.md)

# Menu rapide

Le menu rapide est la seule partie de CheeseDash qui tourne dans le client du jeu : un
petit écran de boutons, chacun étant une commande que tu as enregistrée, ouvert par une
touche. Il existe pour le cas que le dashboard gère mal — tu es debout dans le monde, tu
veux lancer `/gamemode creative` ou te téléporter sur un chantier, et basculer vers un
navigateur coûte plus cher que l'action elle-même.

Ce n'est pas une interface d'administration. Il ne sait ni modifier des zones, ni lire le
journal d'audit, ni rien de ce que fait le dashboard. Il envoie des chaînes de commande,
et c'est le serveur qui décide si tu as le droit de les exécuter.

Quatre petits fichiers :

| Fichier | Lignes | Rôle |
|---|---|---|
| `client/CheeseDashClient.java` | 50 | l'entrypoint client et la touche |
| `client/QuickMenuScreen.java` | 183 | l'écran lui-même |
| `client/QuickMenuConfig.java` | 79 | lit et écrit le fichier de raccourcis |
| `network/QuickMenuPayload.java` | 26 | l'unique paquet client vers serveur |

---

## Installer et ouvrir

Rien à installer au-delà de CheeseDash lui-même. Le `fabric.mod.json` du mod déclare
`"environment": "*"` avec un entrypoint `main` et un entrypoint `client` : le même jar
est donc le mod serveur et le mod client. Pose-le dans le dossier `mods/` du client, à
côté de Fabric API, et la touche apparaît.

**La touche n'est associée à rien par défaut.** `CheeseDashClient` l'enregistre avec
`GLFW_KEY_UNKNOWN`, donc rien ne se passe tant que tu ne l'as pas assignée : *Options →
Commandes → Touches*, catégorie **CheeseDash**, entrée **Ouvrir le menu rapide
CheeseDash** (en anglais, *Open CheeseDash Quick Menu*). C'est délibéré : un outil
d'administration qui s'empare d'une touche au premier lancement est un outil qui vole une
frappe que quelqu'un voulait pour autre chose.

Réappuyer sur la touche quand le menu est ouvert le referme, et c'est géré deux fois : le
gestionnaire de tick fait la bascule, et le `keyPressed` de l'écran ferme sur la même
touche. `Échap` le ferme comme n'importe quel écran.

> Le jeu **ne se met pas en pause** quand le menu est ouvert (`shouldPause()` renvoie
> `false`). Sur un monde solo, le monde continue de tourner derrière le panneau. Sur un
> serveur, c'est de toute façon le seul comportement possible.

### Qui a le droit de l'ouvrir

```java
if (!client.player.hasPermissionLevel(2)) continue;
```

Le client vérifie le niveau de permission du joueur avant d'ouvrir l'écran, et seuls les
opérateurs de niveau 2 ou plus l'obtiennent. Pour les autres, la touche ne fait
strictement rien : pas d'écran, pas de message, pas de son. Ce n'est pas un contrôle de
sécurité — une vérification côté client ne l'est jamais, et un client modifié pourrait
ouvrir l'écran quand même — c'est là pour qu'un non-opérateur qui aurait le mod installé
ne tombe pas sur un menu dont chaque bouton échoue.

Le vrai contrôle est côté serveur, plus bas.

---

## L'utiliser

L'écran est un panneau centré, large de 300 pixels, aux couleurs du dashboard : un fond
sombre `#131620`, une bordure `#252836`, un titre ambre, sur un voile noir translucide.
Les raccourcis sont disposés sur **deux colonnes**, chacun étant un bouton portant ton
libellé, avec à sa droite une étroite croix `✕` qui le supprime sans confirmation.

Sans aucun raccourci configuré, le panneau affiche :

```text
Aucun raccourci configuré. Appuyez sur '+' pour en ajouter un.
```

```text
No shortcuts configured. Press '+' to add one.
```

Sous la grille se trouve **+ Ajouter un raccourci**. Il remplace le panneau par un
formulaire à deux champs :

| Champ | Longueur max | Texte indicatif |
|---|---|---|
| libellé | 64 caractères | `Nom du raccourci...` |
| commande | 256 caractères | `/commande...` |

**Ajouter** enregistre et revient à la grille ; **Annuler** revient sans enregistrer. Les
deux champs sont nettoyés de leurs espaces et doivent être non vides — si l'un est vide,
**Ajouter** ne fait rien, sans message d'erreur. Le champ de libellé prend le focus à
l'ouverture du formulaire, tu peux donc taper directement.

Cliquer sur un raccourci envoie sa commande au serveur et referme le menu
immédiatement. L'écran n'attend aucun résultat et n'affiche aucun retour : ce que produit
la commande, tu le lis dans le chat une fois le menu disparu.

La barre oblique initiale est facultative. Le serveur passe la chaîne à
`executeWithPrefix`, qui accepte une commande avec ou sans elle : `gamemode creative` et
`/gamemode creative` fonctionnent tous les deux.

---

## Configuration

Côté client, dans `config/cheesedash_shortcuts.json` **de l'instance cliente** — pas dans
le répertoire de configuration du serveur. Rien dans le dashboard ne le lit ni ne
l'écrit, et il est par machine, pas par compte.

```json
{
  "shortcuts": [
    { "label": "Créatif",  "command": "/gamemode creative" },
    { "label": "Survie",   "command": "/gamemode survival" },
    { "label": "Jour",     "command": "/time set day" },
    { "label": "Spawn",    "command": "/tp @s 0 100 0" },
    { "label": "Soin",     "command": "/effect give @s minecraft:instant_health 1 10" }
  ]
}
```

Deux champs par entrée, deux chaînes, tous deux obligatoires. Une entrée à laquelle il
manque l'un des deux, ou dont l'un des deux est vide, est **sautée au chargement**, en
silence. Un fichier qui ne se parse pas du tout est également ignoré en silence, puis
**écrasé au prochain ajout ou à la prochaine suppression de raccourci**, ce qui perd tout
ce qu'il contenait. Si tu édites le fichier à la main, valide le JSON avant de rouvrir le
menu.

Le fichier est relu à chaque ouverture du menu (`config.load()` dans le gestionnaire de
tick), donc une modification externe apparaît sans redémarrer le jeu. Il est réécrit
entièrement à chaque ajout et à chaque suppression. L'ordre dans le fichier est l'ordre à
l'écran, de gauche à droite, deux par ligne : éditer le fichier à la main est donc la
façon de réordonner les raccourcis, car l'écran n'offre aucun moyen de les déplacer.

---

## Ce qui se passe côté serveur

Cliquer sur un raccourci envoie un payload personnalisé `cheesedash:quick_menu_cmd`
portant une seule chaîne. Le récepteur est enregistré dans `CheeseDashMod.onInitialize`,
sur l'entrypoint principal : il est donc actif sur tout serveur faisant tourner
CheeseDash, que quiconque ait la moitié cliente ou non.

```java
ServerPlayNetworking.registerGlobalReceiver(QuickMenuPayload.ID, (payload, context) -> {
    ServerPlayerEntity player = context.player();
    String command = payload.command();
    if (!player.hasPermissionLevel(2)) {
        LOGGER.warn("[CheeseDash] QuickMenu: {} tried to execute '{}' without permission",
            player.getName().getString(), command);
        return;
    }
    context.player().server.execute(() -> {
        LOGGER.info("[CheeseDash] QuickMenu: {} executing '{}'",
            player.getName().getString(), command);
        player.server.getCommandManager().executeWithPrefix(player.getCommandSource(), command);
    });
});
```

Quatre choses à en retenir.

**Le contrôle de permission est côté serveur, et c'est celui qui compte.** Un
non-opérateur qui forge le paquet n'obtient rien d'autre qu'une ligne `WARN` qui le nomme
avec la commande tentée :

```text
[CheeseDash] QuickMenu: Griefer tried to execute '/op Griefer' without permission
```

**La commande s'exécute en tant que joueur, pas en tant que console.** La source de
commande est `player.getCommandSource()`, donc `@s` désigne le joueur, les coordonnées
relatives partent de là où il se tient, et la commande porte son niveau de permission à
lui. Un opérateur ne peut atteindre, par le menu rapide, rien qu'il n'aurait pu taper
dans le chat. C'est l'inverse de `POST /api/v1/server/command`, qui s'exécute en tant que
console.

**L'exécution est confiée au thread serveur** par `server.execute(...)`, parce que le
paquet arrive sur un thread netty et que toucher au jeu depuis là est la bonne façon de
corrompre un monde.

**C'est journalisé, pas audité.** Le refus comme l'exécution partent dans le log du
serveur, en `WARN` et en `INFO`. Rien n'atteint `config/cheesedash_audit.json` : l'activité
d'un opérateur dans le menu rapide est donc invisible dans la vue d'audit du dashboard —
voir [journal d'audit](audit.fr.md#ce-qui-nest-pas-enregistré). Pour savoir qui a lancé
quoi depuis le menu, grep le log du serveur :

```bash
grep "QuickMenu" logs/latest.log
```

### Aucune validation de la chaîne de commande

Le payload est une chaîne simple, jusqu'aux 256 caractères que le champ de texte
autorise, et le serveur la passe telle quelle au dispatcher de commandes. Il n'y a ni
liste blanche, ni contrôle de longueur côté serveur, ni analyse avant exécution. Ce n'est
pas une élévation de privilèges — le dispatcher applique à chaque commande son propre
niveau de permission, comparé à celui du joueur — mais cela fait du paquet une primitive
générique « exécute cette commande en mon nom » pour quiconque est déjà au niveau de
permission 2. Un compte opérateur compromis peut faire par ce paquet tout ce qu'il
pourrait faire dans le chat, et la seule trace est le log du serveur.

---

## Désinstaller la moitié cliente

Retire le jar du dossier `mods/` du client, ou laisse-le : sans raccourci configuré et
sans touche assignée, la moitié cliente coûte une entrée de raccourci clavier et rien
d'autre. `config/cheesedash_shortcuts.json` reste sur le disque et t'appartient.

Le serveur continue d'écouter le paquet tant que CheeseDash y est installé, ce qui est
sans danger : un client qui ne l'envoie pas ne change rien, et un client qui l'envoie est
contrôlé.

---

← [Journal d'audit](audit.fr.md) · [Architecture](architecture.fr.md) →
