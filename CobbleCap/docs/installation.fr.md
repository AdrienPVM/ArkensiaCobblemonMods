[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

## De quel côté

**Côté serveur obligatoire, côté client facultatif.** Le `fabric.mod.json` déclare
`"environment": "*"`, donc le même jar fonctionne des deux côtés, et il contient à la fois
la logique serveur et le HUD, les mixins et la commande `/pasturehide` du client.

Installe-le sur le serveur et la limite est imposée à tout le monde, clients vanilla
compris. Installe-le aussi sur un client et ce joueur gagne en plus le badge de limite,
`/pasturehide`, les poses de statue figées et les textures de statue stylisées. Rien ne se
désynchronise dans un cas comme dans l'autre — voir [le côté client](client.fr.md).

## Prérequis

| | Version | Nécessaire |
|---|---|---|
| Minecraft | 1.21.1 | obligatoire |
| Java | 21 | obligatoire |
| [Fabric Loader](https://fabricmc.net/use/) | ≥ 0.15.11 | obligatoire — `depends` |
| [Fabric API](https://modrinth.com/mod/fabric-api) | n'importe laquelle | obligatoire — `depends` |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | 1.6.1 | `suggests`, mais le mod ne fait rien sans lui |
| [CheeseDash](../../CheeseDash/) | 1.0.0 | `suggests` — nécessaire pour le module dashboard |
| [Radical Cobblemon Trainers](https://modrinth.com/mod/rctmod) | 0.16.11-beta | pas déclaré du tout, indispensable aux champions |
| FTB Quests | n'importe laquelle | pas déclaré, facultatif |
| [CheeseGuard](../../CheeseGuard/) | 1.0.0 | pas déclaré, facultatif — seulement pour la zone de feux d'artifice |
| [CobbleCheeseScore](../../CobbleCheeseScore/) | 1.0.0 | pas déclaré, facultatif — lit les exports de classement |

Seuls trois de ces éléments sont de vraies dépendances Fabric. Tout le reste est atteint par
`Class.forName` et réflexion, et c'est pour cela que le mod se charge proprement sur un
serveur qui n'en a aucun — en en faisant proportionnellement moins.

⚠️ **rctmod est le prérequis réel que personne ne déclare.** Le système de champions
identifie un champion comme un PNJ de dresseur rctmod. Sans rctmod il n'y a rien à battre,
donc la limite ne monte jamais d'elle-même et seul `/cobblecap set` la déplace. Il est
absent à la fois de `depends` et de `suggests` dans le `fabric.mod.json`, ce qui est un
oubli plutôt qu'une décision.

Le module dashboard, lui, est un cas différent : `CheeseDash` est une dépendance **à la
compilation**, résolue depuis `../CheeseDash/build/libs` par un glob. CheeseDash doit donc
être *construit* avant CobbleCap, même s'il n'est qu'en `suggests` à l'exécution.

## Construire

```bash
cd CheeseDash   && ./gradlew build     # CobbleCap compile contre ce jar
cd ../CobbleCap && ./gradlew build
```

La dépendance est un glob `fileTree` sur `cheesedash-*.jar`, pas un nom de fichier figé :
n'importe quelle build de CheeseDash convient. `tools/build-all.sh`, à la racine du dépôt,
parcourt chaque mod dans l'ordre des dépendances si tu préfères ne pas y penser.

Le résultat est `CobbleCap/build/libs/cobblecap-1.0.0+1.21.1.jar` — la version vient de
`mod_version` dans `gradle.properties`. Ignore `cobblecap-1.0.0+1.21.1-sources.jar`.

Cobblemon est récupéré depuis le Maven Modrinth en `compileOnly`, si bien qu'un clone propre
se construit sans aucun téléchargement manuel et que rien de tiers n'est redistribué.

## Installer

Dépose le jar dans le `mods/` du serveur, à côté de Fabric API, Cobblemon, rctmod et — si tu
veux le dashboard — CheeseDash. Supprime d'abord tout `cobblecap-*.jar` plus ancien : deux
versions du même mod dans `mods/` sont un échec de démarrage, pas un avertissement.

Pour la moitié client, dépose le même jar dans le `mods/` du client.

## Ce que crée le premier démarrage

```text
config/
├── cobblecap/
│   ├── config.json        ← écrit avec les valeurs par défaut
│   ├── champions.json     ← écrit comme {"champions": []}
│   └── players.json       ← PAS créé avant la première connexion d'un joueur
├── cobblecap_tenures.json ← écrit vide
├── cobblecheesescore_external_conseil4_first.json
├── cobblecheesescore_external_conseil4_fastest.json
├── cobblecheesescore_external_conseil4_ranked_champions.json
└── cobblecheesescore_external_conseil4_ranked_failures.json
```

`cobblecap_legends.json`, `cobblecap_chronos.json` et `cobblecap_dioramas.json` ne sont
créés que lorsqu'il y a quelque chose à y mettre. Côté client,
`config/cobblecap_client.properties` apparaît à la première utilisation de `/pasturehide`.

Chaque champ de `config.json` et de `champions.json` est documenté dans
[configuration](configuration.fr.md).

## Prouver qu'il est chargé

Log serveur, pendant le démarrage :

```text
[CobbleCap] Config loaded (startingCap=20, enabled=true)
[CobbleCap] Created empty champions.json
[CobbleCap] No players.json found — starting with empty storage
[CobbleCap] No legends file — starting fresh hall of fame
[CobbleCap] Tenures loaded: 0 (0 current)
[CobbleCap] Initialized — startingCap=20, enabled=true, champions=0
```

C'est cette dernière ligne qu'il faut chercher. Puis, une fois le monde démarré :

```text
[CobbleCap] Conseil 4 scoreboards ready: 4 boards (classic ×2 + ranked ×2)
[CobbleCap] StatueManager initialized (0 current-champion anchor(s), 0 doppel(s) reattached)
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Cobblemon candy XP hook registered on field: EXPERIENCE_CANDY_USE_PRE
[CobbleCap] Battle pre-check hook registered on field: BATTLE_STARTED_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

Ces quatre lignes de hook sont le vrai test de santé, parce qu'elles prouvent que
l'abonnement par réflexion au bus d'événements de Cobblemon a réussi. Sans Cobblemon tu
obtiens une seule ligne à la place :

```text
[CobbleCap] Cobblemon not found — XP cap enforcement disabled
```

Et si Cobblemon est présent mais a renommé l'un de ses champs d'événement, un avertissement
correspondant (`Could not find XP event field in CobblemonEvents …`). C'est la ligne à
chercher après chaque mise à jour de Cobblemon.

Côté client :

```text
[CobbleCap] Client initializing...
[CobbleCap] Client initialized — HUD registered, /pasturehide enabled (hidden=false)
```

## Vérifier en jeu

1. Connecte-toi. Le log doit afficher
   `[CobbleCap] Player joined: <nom> (cap=20)`.
2. Tape `/cobblecap`. Tu dois voir `Votre limite de niveau : 20` et, avec une liste de
   champions vide, `Tous les champions ont été vaincus !`.
3. Avec le mod sur ton client, un petit badge affichant `20` doit se trouver dans le coin
   inférieur droit. `/cobblecap hud position top-left` le déplace.
4. Tape `/cobblecap statue styles`. Treize clés doivent revenir. Cela prouve que le
   sous-système de statues s'est initialisé.
5. Avec CheeseDash en marche, interroge la liste des modules — CobbleCap doit apparaître
   sous le préfixe `cobblecap`. Voir [l'API dashboard](dashboard.fr.md).

C'est un mod *chargé*, pas un mod *configuré*. Rien ne verrouille rien tant que tu n'as pas
rédigé `champions.json` ; [la prise en main](getting-started.fr.md) y conduit pas à pas.

## Avant le premier morph de champion

⚠️ Deux choses à faire sur un nouveau serveur, dans cet ordre, avant que quiconque puisse
gagner :

1. **Change `datapackName`.** `src/main/java/com/cobblecap/logic/RctTrainerMorpher.java`
   ligne 57 contient `public static String datapackName = "CacademyMons";`. Ce n'est pas une
   clé de configuration — édite le source et reconstruis. Tant que ce n'est pas fait, chaque
   morph abandonne avec
   `[CobbleCap] Datapack 'CacademyMons' not found in any of: [...]`. Détails dans
   [le morphing du champion](champion-morphing.fr.md#ce-que-tu-dois-changer).
2. **Lance `/cobblecap champion backup_now`.** Il copie le JSON de dresseur et la skin
   d'origine dans `config/cobblecap_regis_backup/`. Sans cette copie il n'y a aucun retour
   possible depuis un morph, et `ensureBackup` refuse de sauvegarder un fichier déjà morphé.

## Désinstaller

Arrête le serveur, retire le jar, redémarre. Rien dans le monde n'a besoin du mod pour se
charger, et il n'y a aucun item, bloc ni type d'entité enregistré à laisser derrière.

Ce qui survit :

| Laissé derrière | Supprimer avec |
|---|---|
| `config/cobblecap/` | supprime le répertoire |
| `config/cobblecap_*.json` et `config/cobblecap_regis_backup/` | supprime |
| `config/cobblecheesescore_external_conseil4_*.json` | supprime |
| `config/cobblecap_client.properties` sur chaque client | supprime |
| 5 objectifs de scoreboard | `/scoreboard objectives remove conseil4_first_finish` et de même pour `conseil4_fastest_time`, `conseil4_ranked_champions`, `conseil4_ranked_failures`, `cobblecap_mode` |
| 13 équipes de scoreboard `cgcap_<style>` | `/team remove cgcap_natural` et ainsi de suite |
| les entités de statue | ⚠️ **lance `/cobblecap statue wipe_all` avant de retirer le jar** |
| un JSON de dresseur rctmod morphé | ⚠️ **lance `/cobblecap champion restore_original` avant de retirer le jar** |
| les advancements `cobblecap:` accordés aux joueurs | ils deviennent des identifiants inconnus, sans conséquence |

Ces deux derniers points comptent. Les trackers de statue sont des **supports d'armure
marqueurs invisibles** : une fois le mod parti, plus rien ne les affiche et plus rien ne peut
les retrouver, et ils resteront dans ton monde indéfiniment. Et un JSON de dresseur morphé
reste morphé — le boss final de la ligue garde l'équipe du dernier vainqueur pour toujours,
sans plus aucun CobbleCap pour la restaurer.

Les Pokémon plafonnés ne sont pas affectés : leur niveau et leur XP sont des valeurs réelles,
déjà ramenées dans les bornes. Ils reprennent simplement une progression normale.

---

← [CobbleCap](../README.fr.md) · [Prise en main](getting-started.fr.md) →
