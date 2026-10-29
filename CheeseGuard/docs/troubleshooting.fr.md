[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Symptôme, cause, remède. Les pannes propres à ce mod sont à la fin, et ce sont celles que
tu rencontreras le plus souvent.

---

## Le serveur ne démarre pas, et le log parle d'un mixin

**Cause.** CheeseGuard applique 44 mixins, dont 40 côté serveur, et
`cheeseguard.mixins.json` met `"required": true` avec `defaultRequire: 1`. Un mixin qui ne
trouve pas sa méthode cible arrête le jeu plutôt que de se charger à moitié protégé. Deux
déclencheurs : une version de Minecraft autre que 1.21.1, ou un mod tiers qui a renommé la
classe visée par un mixin inter-mods.

**Remède.** Trouve la première `MixinApplyError` ou `InvalidInjectionException` dans
`logs/latest.log` et lis le nom de la classe de mixin qu'elle donne. S'il s'agit de
`JadeTooltipHideMixin`, `JourneyMapMinimapHideMixin`, `CobblemonNametagHideMixin` ou
`CSAFuzzyMatchFixMixin`, le mod tiers visé a changé — reviens à une version antérieure de
ce mod en attendant une mise à jour de CheeseGuard. S'il s'agit d'une cible vanilla, tu
n'es pas en 1.21.1.

La liste groupée de ce que touche chaque mixin, et le mod tiers dont chaque groupe dépend,
est dans [l'architecture](architecture.fr.md#les-44-mixins).

---

## Le serveur s'arrête avec « Mod cheeseguard requires cheesedash »

**Cause.** CheeseDash est une dépendance **obligatoire** dans `fabric.mod.json`, pas
optionnelle. CheeseGuard ne se chargera pas sans lui, même si tu n'ouvres jamais le
dashboard.

**Remède.** Mets le jar de CheeseDash dans `mods/` à côté. La liste complète des prérequis
est dans [l'installation](installation.fr.md).

---

## CheeseGuard est chargé mais aucune commande n'existe

**Cause.** En général, le mod n'est que dans le `mods/` du client, ou le jar copié est la
variante `-sources` ou `-dev`, qui ne contient pas de code utilisable.

**Remède.** Vérifie que `[CheeseGuard] CheeseGuard loaded!` figure dans le log du
**serveur**, et que le fichier copié est bien `cheeseguard-1.0.0+1.21.1.jar` sans suffixe.
Souviens-toi ensuite que la commande racine est **`/cg`**, pas `/cheeseguard` — il n'y a
pas d'alias long.

---

## Une commande ne fait rien du tout, sans message

**Cause.** Presque tous les handlers `/cg` commencent par
`ctx.getSource().getPlayerOrThrow()` et retournent `0` quand il n'y a pas de joueur, sans
envoyer d'erreur. Lancées depuis la console, depuis un bloc de commande ou via RCON, ces
sous-commandes ne font rien, silencieusement.

**Remède.** Lance-les en tant que joueur, ou enveloppe-les dans
`/execute as <joueur> at @s run cg …`. Les sous-commandes qui fonctionnent depuis la
console sont le sous-arbre `state`, `playerworld`, `event announce`, `event score`,
`event snapshot`, `event template`, `pasture`, `restoreinv`, `hotpotato` et tout l'arbre
`/prison`.

---

## Un joueur a modifié les flags d'une zone

**Cause.** Sur un jar construit avant le 29 octobre 2026, `/cg flag`, `/cg addmember`,
`/cg removemember` et `/cg group *` ne portaient **aucune garde de permission**. N'importe
quel joueur pouvait les lancer sur n'importe quel identifiant de zone, et ces identifiants
dérivent du nom de la zone — donc ils se devinent.

**Remède.** Mets le jar à jour : ces quatre nœuds sont au niveau 2 désormais. Les niveaux
sont compilés dans l'arbre Brigadier, il n'y a donc pas de réglage : sur un jar plus
ancien, bloque ces nœuds avec un mod de permissions ou garde le serveur en liste blanche.
Le tableau des gardes actuelles est dans
[les commandes](commands.fr.md#niveaux-de-permission).

---

## `/cg flag` a accepté ma valeur mais le flag se comporte à l'envers

**Cause.** La valeur passe par `Boolean.parseBoolean`. Seule la chaîne exacte `true` (sans
distinction de casse) est vraie. `yes`, `1`, `allow`, `on`, et toute faute de frappe
deviennent **`false`**, avec un message de confirmation enjoué.

**Remède.** Utilise exactement `true`, `false` ou `inherit`. Vérifie avec `/cg info` — qui
n'affiche pas les flags, donc vérifie plutôt depuis le dashboard. `inherit` est aussi
distinct de `false` : il retire le flag, de sorte que la zone retombe sur son groupe puis
sur le défaut du flag.

---

## Toutes mes zones ont disparu après un déménagement du serveur

**Cause.** `regions.json`, `chunks.json`, `chunk_suggestions.json`, `tag_configs.json` et
`prison.json` résolvent leur chemin comme `Paths.get("config", "cheeseguard")` —
relativement au **dossier de travail du processus**, pas au dossier de configuration de
Fabric. Lance le serveur depuis un autre dossier que sa racine et CheeseGuard y écrit un
`config/cheeseguard/` neuf et vide, puis démarre sans aucune zone.

**Remède.** Lance le serveur avec le dossier de travail positionné sur sa racine. Cherche
ensuite un `config/cheeseguard/` égaré à côté de l'endroit d'où ton script de démarrage
s'exécute réellement, et remets les fichiers en place. Détaillé dans
[la configuration](configuration.fr.md).

---

## Des zones ont disparu après un plantage

**Cause.** Les sauvegardes ne sont pas atomiques. Chaque gestionnaire écrit directement sur
le chemin final avec `Files.writeString`, sans fichier temporaire ni renommage. Un plantage
pendant une sauvegarde tronque le fichier, et `RegionStorage.load()` avale l'erreur
d'analyse et démarre avec zéro zone plutôt que de refuser de booter.

**Remède.** Restaure `config/cheeseguard/regions.json` depuis une sauvegarde. S'il n'y en a
aucune, les zones sont perdues. Ajoute `config/cheeseguard/` et
`config/cheeseguard_*.json` à ce qui sauvegarde ton monde — ils ne sont pas dans le dossier
du monde et la plupart des scripts de sauvegarde les oublient.

---

## Une modification d'un fichier de configuration n'a eu aucun effet

**Cause.** Trois raisons différentes selon le fichier.

- **Les fichiers d'état** (`regions.json`, `cheeseguard_events.json`,
  `cheeseguard_dimensions.json`, `prison.json`) appartiennent au serveur en marche, qui les
  garde en mémoire et les réécrit à la prochaine mutation. Ta modification a été écrasée.
- **`cheeseguard_webhook.json`** et **`cheeseguard_pastures.json`** sont lus une seule
  fois, au démarrage du serveur.
- **`cheeseguard_fish_pokemon.json`** se recharge quand le dashboard le demande, mais un
  fichier **mal formé** journalise `Failed to load fish pokemon table` et retombe
  silencieusement sur les valeurs par défaut intégrées.

**Remède.** Arrête le serveur avant d'éditer un fichier d'état. Redémarre après avoir
édité un fichier de réglages. Valide ton JSON — une virgule en trop suffit à faire ignorer
le fichier par le mod sans qu'il te le dise.

---

## Discord ne publie rien

**Cause.** `config/cheeseguard_webhook.json` est absent, ou son `webhook_url` est vide. Un
fichier absent ne produit **aucune ligne de log** — pas même un avertissement — donc une
faute dans le nom du fichier est invisible.

**Remède.** Crée le fichier avec le nom exact, redémarre, et cherche
`Failed to load webhook config` dans le log : cette ligne signifierait que le fichier
existe mais est mal formé. Le format est dans
[la configuration](configuration.fr.md#cheeseguard_webhookjson).

---

## La section CheeseGuard manque dans le dashboard

**Cause.** Une de ces trois choses. CheeseDash n'a pas trouvé l'entrypoint ; CheeseGuard
n'a pas fini de démarrer ; ou tu regardes une page en cache.

**Remède.** L'entrypoint est déclaré
`"cheesedash": ["com.cheeseguard.dashboard.CheeseGuardDashboardModule"]` dans
`fabric.mod.json` : un jar construit depuis ces sources l'a donc toujours. S'il est absent,
c'est que CheeseDash s'est chargé avant que CheeseGuard s'enregistre, ce qui ne devrait pas
arriver et mérite un rapport de bug. Une requête arrivant avant `onServerStart` reçoit
`503 {"error":"CheeseGuard not ready"}` : attends la fin du démarrage et recharge. Confirme
que le module est vivant avec `GET /api/v1/cheeseguard/status`.

---

## Un OPERATOR a supprimé une zone, et je voulais que cela exige SUPER_ADMIN

**Cause.** Aucune route de CheeseGuard ne peut exiger `SUPER_ADMIN`. `ZonesApi`,
`DimensionsApi`, `EventsApi`, `GroupsApi`, `PlayersApi` et `ChunksApi` n'appellent jamais
`WebDashboard.requireWrite` ni `requireAdmin` ; ce qui les garde, c'est le répartiteur de
modules de CheeseDash, qui applique `requireWrite` — `OPERATOR` ou au-dessus — à toute
requête non-`GET` vers un module. La frontière tient donc, et une session `READ_ONLY` ne peut
que lire, mais `OPERATOR` est autant le plafond que le plancher : supprimer une zone,
réinitialiser une dimension depuis un snapshot et modifier un flag sont tous au même niveau.

**Remède.** Considère `OPERATOR` sur ce mod comme un contrôle total et distribue-le en
conséquence ; `READ_ONLY` est le rôle de qui ne doit que regarder. Si une route précise
mérite une barre plus haute, le contrôle doit être ajouté dans le module —
`if (!WebDashboard.requireAdmin(exchange)) return;` en tête du handler concerné, comme
`PrisonApi` le fait pour `requireWrite`. Voir
[l'architecture](architecture.fr.md#faiblesses-connues) et
[l'API dashboard](dashboard.fr.md#ce-que-le-cœur-applique-réellement).

---

## `/prison set` dit que la durée est invalide

**Cause.** `parseDuration` accepte des chiffres suivis de `s`, `m`, `h` ou `d`, concaténés :
`90s`, `30m`, `2h`, `7d`, `1h30m`. Un nombre nu signifie des **minutes**. Tout le reste
renvoie `-1` : une semaine écrite `2w`, un décimal `1.5h`, un espace `2 h`, un signe
négatif.

**Remède.** Écris `14d` plutôt que `2w`, `90m` plutôt que `1.5h`, et sans espace.

---

## Un prisonnier n'est pas téléporté dans la prison

**Cause.** `/prison set` ne téléporte **pas** une cible connectée. La commande enregistre
la peine, envoie le message, et laisse le confinement par tick de `PrisonListener` ramener
le joueur à un tick ultérieur.

**Remède.** Attends quelques secondes. Si rien ne se passe, l'identifiant de zone de prison
de `prison.json` ne correspond plus à une zone de `regions.json` — relance
`/prison setzone <zoneId>`. Si cela ne suffit pas, vérifie que la zone est dans une
dimension où le joueur peut être téléporté.

---

## Un prisonnier libéré réapparaît au mauvais endroit

**Cause.** Aucun point de sortie n'est configuré : les libérations retombent donc sur le
spawn du monde.

**Remède.** Place-toi où tu veux qu'ils réapparaissent et lance `/prison setout`.
`/prison clearout` revient au spawn du monde.

---

## `/cg playerworld delete` répond « Échec de la suppression. »

**Cause.** Presque toujours un verrou de fichier sous Windows. Le dossier du monde de la
dimension ne peut pas être supprimé parce que les handles du monde tout juste déchargé
n'ont pas encore été relâchés.

**Remède.** Attends et relance la commande. Si elle échoue encore, arrête le serveur,
supprime le dossier à la main, puis retire l'entrée correspondante de
`config/cheeseguard_dimensions.json` serveur toujours arrêté. Le dossier par joueur porte
le nom de l'identifiant de dimension, de la forme `<masterDimId>_<uuid8>`.

Une seconde cause, plus rare : `delete` résout un joueur **hors ligne** en cherchant une
dimension dont le nom affiché *contient* le nom tapé, puis en prenant le premier UUID de sa
liste blanche. Une correspondance trop lâche peut viser le mauvais monde, ou aucun.
Supprime pendant que le joueur est connecté, ou fais-le depuis le dashboard.

---

## Un joueur a perdu son inventaire à cause d'un événement

**Cause.** Un événement l'a vidé, et la restitution n'a pas eu lieu — d'ordinaire parce que
le serveur a planté ou que le joueur s'est déconnecté entre le vidage et la restitution.

**Remède.** Dans l'ordre :

1. Fais lancer `/cg event leave` au joueur. Son deuxième repli applique une restauration en
   attente depuis `config/cheeseguard_pending_inv/` et c'est le chemin de récupération
   prévu.
2. Si cela répond *Vous ne participez à aucun événement*, utilise
   `/cg restoreinv list <joueur>` pour voir les archives et
   `/cg restoreinv apply <joueur> <fichier>` pour en remettre une. Les lignes affichées par
   `list` sont cliquables et remplissent la commande `apply` pour toi.
3. Un joueur hors ligne fonctionne aussi — la commande remplace son fichier `playerdata` et
   écrit d'abord une copie de sécurité, qu'elle nomme dans son retour.

Chaque application est journalisée avec l'auteur, la cible et l'archive : la restitution
elle-même est donc auditable. Documentation complète dans
[les commandes](commands-operations.fr.md#cg-restoreinv).

---

## Une capture d'état de zone a gelé le serveur une seconde

**Cause.** `/cg state capture` et `/cg event snapshot save` parcourent chaque bloc de la
boîte sur le thread serveur. Une zone de 100×100×100 représente un million de lectures de
blocs dans un seul tick.

**Remède.** Capture de petites zones, ou capture quand personne n'est connecté. Il n'existe
ni chemin asynchrone ni indication de progression.

---

## `/cg list` affiche une dimension personnalisée comme « End »

**Cause.** La colonne monde est produite par un helper qui compare par sous-chaîne à
`overworld`, `nether` et `end` et affiche le reste brut. Un identifiant de dimension
contenant `end` n'importe où — `arkensia:endless_cave` — est donc étiqueté *End*.

**Remède.** Purement cosmétique ; la zone est stockée avec le bon identifiant de monde.
Lis le vrai identifiant depuis le dashboard ou dans `regions.json`.

---

## `/cg removemember` ou `/cg group delete` a dit que c'était fait, et rien n'a changé

**Cause.** Les deux signalent une réussite sans condition. `removemember` compare les
membres par nom affiché, sans distinction de casse ; un joueur qui a changé de pseudo depuis
ne peut plus être trouvé. `group delete` confirme même pour un groupe qui n'a jamais
existé.

**Remède.** Vérifie avec `/cg info <zoneId>` — compare le nombre de membres avant et après
— ou fais-le depuis le dashboard, qui travaille sur les UUID.

---

## Une commande `/home` ne fait rien, pour un joueur seulement

**Cause.** `HomeCommandMixin` annule toute commande dont le texte commence par `home` pour
un non-opérateur se trouvant dans une dimension dont la configuration a `allowHome`
désactivé. La comparaison porte sur la chaîne brute : `/homeland` et `/homes` sont donc
attrapés aussi.

**Remède.** Active `allowHome` sur la configuration de cette dimension, ou accepte le
comportement. Si le joueur est en prison, c'est plutôt `PrisonCommandMixin` qui annule tout
hors d'une petite liste blanche, et le message est *Vous êtes en prison. Cette commande est
interdite.*

---

## Les alertes de spawn Cobblemon se déclenchent pour le mauvais Pokémon

**Cause.** C'est le bug que `CSAFuzzyMatchFixMixin` existe pour corriger ; si tu le revois,
c'est que le mixin ne s'applique plus — Cobblemon Spawn Alerts a modifié
`AlertHandler.getConfigForPokemon` et le `@Redirect` ne correspond plus.

**Remède.** Cherche le nom de ce mixin dans le log autour du démarrage. Un `@Redirect` en
échec journalise ; un `@Redirect` qui correspond mais au mauvais endroit, non. Compare avec
la version 1.9.2 de ce mod, celle contre laquelle le correctif a été écrit.

---

## Signaler un bug

Les informations qui aident vraiment :

1. **`logs/latest.log`**, pas une capture d'écran. Les 200 premières lignes portent la liste
   des mods et tous les échecs d'application de mixin ; les 200 dernières portent le
   plantage. Les deux comptent.
2. **Les versions** : Minecraft, Fabric Loader, Fabric API, CheeseDash, CheeseGuard, et la
   version de tout mod tiers nommé dans le log — Jade, JourneyMap, Cobblemon, Cobblemon
   Spawn Alerts en particulier.
3. **La commande ou la requête exacte**, copiée, pas reformulée. L'ordre des arguments et
   la casse comptent tous les deux ici.
4. **Le fichier de configuration concerné**, l'URL du webhook Discord retirée avant de le
   publier. C'est un secret.
5. **Si le problème se reproduit sur un serveur où seuls CheeseDash et CheeseGuard sont
   installés.** Vu la quantité de ce mod qui se greffe sur d'autres mods, cette seule
   réponse fait gagner le plus de temps.

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) ·
Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)

---

← [Architecture](architecture.fr.md) · [CheeseGuard](../README.fr.md) →
