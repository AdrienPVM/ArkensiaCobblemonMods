[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

Deux arbres de commandes, enregistrés dans `CobblemonDungeons.onInitialize` et
`DungeonsPartyCommands.register` :

- `/dungeons`, avec `/dungeon` comme alias strictement identique
- `/DungeonsParty`

Il n'existe délibérément aucune commande d'administration pour créer ou modifier des
donjons. L'écriture se fait dans les fichiers de configuration ou depuis
[le dashboard](dashboard.fr.md) ; les joueurs passent par l'interface. C'est aussi pourquoi
rien ici ne lance directement un donjon : le démarrage d'une partie passe par l'écran de
sélection, qui appelle `DungeonManager.startDungeon` pour toi.

⚠️ `/DungeonsParty` est enregistré avec cette casse exacte, et les littéraux Brigadier sont
sensibles à la casse. `/dungeonsparty` n'est pas une commande. La complétion à partir de
`/D` produira la bonne forme.

---

## `/dungeons` · `/dungeon`

Ouvre l'interface de sélection des donjons. Permission : aucune — tout joueur.

```mcfunction
/dungeons
```

Les deux noms sont deux enregistrements distincts avec des sous-arbres identiques, pas un
alias au sens Brigadier. Tout ce qui suit s'applique aux deux.

**Ce que fait la commande.** Elle vérifie le rôle du serveur, vérifie que le joueur a au
moins un Pokémon, puis ouvre `DungeonSelectionGui`. Depuis là, le joueur choisit un donjon,
éventuellement un tier (des boutons du Tier 1 au Tier 5) et confirme, ce qui appelle
réellement `startDungeon`.

**État requis.** La commande est refusée sur un serveur de rôle `dungeon` — voir
[les rôles de serveur](instances.fr.md#les-parties-cross-serveur). Elle exige une source
joueur : lancée depuis la console, elle échoue sur l'erreur Brigadier standard
« requires a player ».

**Messages d'échec.**

| Message | Cause |
|---|---|
| *Cette commande n'est disponible que sur le serveur principal. Utilisez /dungeons leave pour quitter le donjon.* | Le rôle du serveur est `dungeon` |
| *Vous avez besoin d'un ou plusieurs pokémon dans votre équipe pour entrer dans les donjons* | L'équipe Cobblemon du joueur est vide |
| *Une erreur interne est survenue : <message>* | Toute exception pendant l'ouverture de l'interface ; la trace part dans le log sous `Error executing /dungeons command` |

### `/dungeons leave`

Quitte le donjon dans lequel le joueur se trouve. Permission : aucune — tout joueur.

```mcfunction
/dungeons leave
```

C'est la seule sous-commande sans restriction de rôle de serveur, et c'est voulu : c'est ce
qu'un joueur sur le serveur de donjon utilise pour en ressortir.

**Effets de bord.** Le chemin de sortie complet décrit dans
[le cycle de vie des instances](instances.fr.md#quitter) : le HUD est masqué, puis soit une
téléportation vers `returnPos` (`SAME_SERVER`), soit un `DungeonJoinPayload` suivi d'un
transfert vers `mainServer` 40 ticks plus tard (`CROSS_SERVER`). Si le boss n'est pas mort,
la progression et les objets récupérés dans le donjon ne sont **pas** sauvegardés, et le
joueur en est informé. Quand le dernier membre part, les entités du donjon sont nettoyées.

**Exemple.**

```mcfunction
/dungeons leave
```

```text
Vous avez quitté ou échoué le donjon. Vos objets et votre progression n'ont PAS été sauvegardés.
```

**Messages d'échec.**

| Message | Cause |
|---|---|
| *Vous n'êtes pas actuellement dans une instance de donjon.* | Aucune instance enregistrée pour ce joueur |

Les exceptions sont capturées et journalisées sous
`Error executing /dungeons leave command`, sans rien afficher au joueur.

### `/dungeons sql test`

Rapporte l'état de la connexion MariaDB. Niveau de permission **2**. Refusée sur un serveur
de rôle `dungeon`.

```mcfunction
/dungeons sql test
```

**Ce que fait la commande.** Elle lit `config.json`, puis soit signale que SQL est
désactivé, soit prend une connexion dans le pool HikariCP et la valide avec un délai de 5
secondes. Rien n'est écrit.

**Exemples.** Avec SQL désactivé :

```text
SQL is disabled in config.json
```

En état de marche :

```text
SQL Connection is active and healthy! (Table prefix: cobblemon_dungeons_)
```

Activé mais cassé — l'erreur est celle rapportée par le pool au démarrage :

```text
SQL is enabled but the connection failed. Error: Socket fail to connect to localhost:3306
```

Ces trois lignes sont colorées par des codes `§` dans le source Java plutôt que par un
fichier de langue : elles restent donc en anglais même sur un client français.

**Ce que voit un joueur sans la permission.** Rien : Brigadier masque les nœuds dont le
`requires` échoue, donc `/dungeons sql` n'apparaît pas en complétion et le taper donne
l'erreur générique *Commande inconnue ou incomplète*.

### `/dungeons partydebug <on|off|dump|status|ping>`

Diagnostics de la synchronisation de groupe entre serveurs. Niveau de permission **2** sur
tout le sous-arbre. Refusée sur un serveur de rôle `dungeon`.

```mcfunction
/dungeons partydebug on
/dungeons partydebug off
/dungeons partydebug dump
/dungeons partydebug status
/dungeons partydebug ping <server>
```

| Sous-commande | Source | Ce qu'elle fait | Retour |
|---|---|---|---|
| `on` | quelconque | Active la journalisation verbeuse dans `PartySyncHandler` | *Débogage de sync de groupe activé.* |
| `off` | quelconque | La désactive | *Débogage de sync de groupe désactivé.* |
| `dump` | joueur | Affiche l'équipe Cobblemon sérialisée du joueur dans le chat et dans le log | *Débogage de sync de groupe envoyé dans le log et le chat.* |
| `status` | joueur | Affiche l'état de synchronisation courant dans le chat | aucun — la sortie *est* la réponse |
| `ping <server>` | joueur | Envoie un `PartyDebugPayload` à travers le proxy vers le serveur nommé | *Ping de débogage de sync envoyé à <server>.* |

`dump`, `status` et `ping` exigent une source joueur et échouent depuis la console.

**Argument de `ping`.**

| Nom | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `server` | `string` Brigadier (mot pouvant être mis entre guillemets) | oui | Tout nom de serveur du proxy | — |

Aucune complétion n'est fournie — le mod n'a aucune liste des serveurs du proxy. Un nom
contenant une espace doit être entre guillemets :
`/dungeons partydebug ping "dungeon 1"`.

Un ping ne produit de réponse que si le forwarder Velocity est installé et que le serveur
nommé est joignable. Le silence est le mode d'échec normal, et il n'y a aucun message de
délai dépassé.

```mcfunction
/dungeons partydebug ping dungeonsraids
```

```text
Ping de débogage de sync envoyé à dungeonsraids.
```

---

## `/DungeonsParty`

Gestion des groupes pour les parties de donjon. Ces groupes sont le regroupement propre au
mod, sans rapport avec une équipe Cobblemon de Pokémon. Permission : aucune — tout joueur.
**Tout l'arbre** est refusé sur un serveur de rôle `dungeon`, y compris `leave` : les
groupes se gèrent sur le serveur principal uniquement.

```mcfunction
/DungeonsParty
```

Sans argument : crée un groupe si le joueur n'en a pas, puis ouvre l'interface de groupe. S'il
en a déjà un, l'interface s'ouvre directement.

**Messages d'échec.**

| Message | Cause |
|---|---|
| *Vous avez besoin d'un ou plusieurs pokémon dans votre équipe pour entrer dans les donjons* | Équipe Cobblemon vide — vérifié par chacune des sous-commandes ci-dessous également |

À la création : *Groupe créé !*

### `/DungeonsParty create`

Crée un groupe sans ouvrir l'interface.

```mcfunction
/DungeonsParty create
```

| Message | Cause |
|---|---|
| *Groupe créé !* | Succès |
| *Vous êtes déjà dans un groupe !* | Le joueur est déjà dans un groupe |
| *Vous avez besoin d'un ou plusieurs pokémon dans votre équipe pour entrer dans les donjons* | Équipe Cobblemon vide |

Note que la création peut aussi échouer silencieusement : si `createParty` renvoie null,
aucun message n'est envoyé.

### `/DungeonsParty invite <player>`

Invite un joueur dans ton groupe.

| Nom | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | sélecteur d'entité Brigadier, forme joueur | oui | Un seul joueur en ligne — un nom, `@p`, `@r`, ou un sélecteur résolvant vers exactement un joueur | — |

La complétion propose les noms des joueurs en ligne. Un sélecteur qui correspond à zéro ou à
plusieurs joueurs est rejeté par Brigadier avant l'exécution de la commande.

```mcfunction
/DungeonsParty invite Alex
```

```text
Invitation envoyée à Alex
```

Le joueur invité reçoit un message cliquable : *<invitant> vous a invité dans son groupe de
donjon !* avec l'infobulle *Cliquez pour rejoindre le groupe de <invitant>*.

**Effets de bord.** Une invitation est stockée en attente chez la cible. `invitePlayer` peut
refuser pour ses propres raisons — un groupe plein, par exemple, qui envoie *Votre groupe
est plein !* — et dans ce cas aucun retour *Invitation envoyée* n'est affiché.

### `/DungeonsParty join [inviter]`

Accepte une invitation.

| Nom | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `inviter` | sélecteur d'entité Brigadier, forme joueur | non | Un seul joueur en ligne | — |

Sans argument, les invitations en attente du joueur sont consommées dans l'ordre où le
gestionnaire les détient. Avec un `inviter`, seule une invitation venant de ce joueur précis
est acceptée.

```mcfunction
/DungeonsParty join
```

```text
Vous avez rejoint le groupe !
```

| Message | Cause |
|---|---|
| *Vous avez rejoint le groupe !* | Succès, sans argument |
| *Vous avez rejoint le groupe de <invitant> !* | Succès, avec un argument |
| *Aucune invitation en attente.* | Sans argument, et rien en attente |
| *Aucune invitation de <joueur>.* | Avec un argument, et aucune invitation de ce joueur |
| *Le groupe est plein* / *Échec de la tentative de rejoindre le groupe.* / *Échec : le groupe a peut-être été dissous.* | Émis par `DungeonsPartyManager` selon la raison du refus |

### `/DungeonsParty leave`

Quitte le groupe.

```mcfunction
/DungeonsParty leave
```

La commande elle-même n'affiche rien ; c'est le gestionnaire qui parle. Celui qui part voit
*Vous avez quitté le groupe.* Les membres restants voient *<joueur> a quitté le groupe.*, et
si le partant était le chef, le groupe est dissous avec *Le groupe a été dissous car le chef
est parti.*

Il n'y a aucune confirmation et aucun message d'échec pour un joueur qui n'est dans aucun
groupe.

---

## Ce qui est dans l'interface et pas dans une commande

L'écran de sélection porte des actions sans équivalent en commande :

- choisir un donjon et le lancer, à un tier donné
- l'état « recherche de groupe », qui te signale aux autres joueurs
- l'échange de tickets — les quatre conversions, documentées dans
  [les tickets](tickets.fr.md)
- l'invite de combat de boss affichée quand tu entres dans un portail activé

Les actions d'administration sans équivalent en commande — CRUD de configuration,
rechargement, échec forcé, sortie forcée — vivent dans [le dashboard](dashboard.fr.md).

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
