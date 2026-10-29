[← CheeseVanity](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Contrôles

**CheeseVanity n'enregistre aucune commande.** Pas de `/cheesevanity`, pas de
sous-commande, pas de commande d'administration, et rien qui demande un niveau de
permission. Chercher `CommandRegistrationCallback` ou `literal(` dans le code source ne
renvoie rien, parce que le seul contrôle offert à l'utilisateur est un appui de touche dans
un écran d'inventaire.

Cette page documente cet appui de touche comme les autres mods du dépôt documentent leurs
commandes, parce que pour un joueur il joue le même rôle. Contrairement au reste du dépôt,
cette page est écrite pour les **joueurs**, pas pour les administrateurs : chaque joueur
pilote sa propre visibilité, et aucun privilège d'opérateur n'intervient où que ce soit.

---

## La touche de bascule

```text
=    (touche égal / plus, curseur sur un slot dans un écran d'inventaire ouvert)
```

Ouvre n'importe quel écran qui montre ton armure ou tes slots Trinkets — ton inventaire, le
panneau latéral de Trinkets, l'écran d'un sac à dos, tout ce qui est construit sur le
`HandledScreen` vanilla —, place le curseur de la souris sur un slot, et appuie sur `=`.

| Entrée | Valeur | Requise | Remarques |
|---|---|---|---|
| touche | `GLFW_KEY_EQUAL` (la touche `=` / `+`) | oui | **codée en dur**. Ce n'est pas un raccourci Minecraft et elle n'apparaît pas dans Options → Commandes, donc elle ne peut pas être réassignée en jeu |
| slot survolé | n'importe quel slot que le mod sait résoudre | oui | un slot Trinkets, ou l'un des quatre slots d'armure vanilla de ton inventaire |

La touche est lue dans `HandledScreenMixin.onKeyPressed`, à la tête de
`HandledScreen.keyPressed`. Sur un slot que le mod reconnaît, l'appui est consommé et
n'atteint jamais le reste de l'écran. Sur n'importe quel autre slot, ou sans slot sous le
curseur, le mixin rend la main aussitôt et `=` se comporte comme d'habitude.

### Permission

Aucune. N'importe quel joueur, opérateur ou non, peut basculer ses propres slots. Un joueur
ne peut jamais agir que sur son propre modèle : le serveur prend la cible dans
`context.player()` du paquet et ignore ce que le client pourrait prétendre, donc le payload
ne transporte aucun UUID à falsifier.

Il n'y a pas non plus de passe-droit administrateur. Rien ne permet à un opérateur de
masquer ou de réafficher l'équipement d'un autre joueur, et rien ne permet d'interdire le
mod à un joueur en particulier. Le seul levier d'un administrateur est la configuration,
qui s'applique à tout le serveur — voir [configuration.fr.md](configuration.fr.md).

### Ce que fait la touche

1. Le client résout le slot survolé en une slot key et envoie
   `cheesevanity:toggle_slot` au serveur.
2. Le serveur valide la clé, l'inverse dans ton ensemble, et écrit immédiatement
   `config/cheesevanity_data.json` sur le disque.
3. Le serveur diffuse ton nouvel ensemble à tous les joueurs connectés, donc tous ceux qui
   font tourner le mod rendent ton modèle à nouveau.
4. Tu reçois un message en barre d'action qui confirme le nouvel état.

La séquence complète, et surtout qui finit par voir le changement, est sur la page
[qui voit quoi](visibility.fr.md).

### Exemple

Survole le slot `back` de ton panneau Trinkets, où se trouve une cape, et appuie sur `=` :

```text
[CheeseVanity] chest/back hidden
```

en rouge, dans la barre d'action. Le slot de l'écran porte maintenant un remplissage rouge
translucide et une croix rouge, la cape disparaît de ton modèle en F5, et elle disparaît
aussi pour tous les autres joueurs moddés. La cape elle-même n'est pas touchée : elle
reste équipée et continue de faire ce qu'elle fait.

Appuie à nouveau sur `=` sur le même slot :

```text
[CheeseVanity] chest/back visible
```

en vert. La surcouche et le masquage ont disparu tous les deux.

### Échecs

Il n'y a aucun message d'erreur. Chaque refus est silencieux côté client, et c'est la
première chose à savoir quand on a l'impression que le mod est cassé.

| Ce que tu vois | Cause |
|---|---|
| rien du tout, aucun message | le slot survolé n'a pas pu être résolu en slot key — ce n'est ni un slot Trinkets ni l'un de tes quatre slots d'armure |
| rien du tout, aucun message | la clé est résolue, mais la configuration l'interdit : `enableTrinkets` vaut `false` pour un slot de trinket, `enableVanillaArmor` vaut `false` pour un slot d'armure (**c'est le défaut**), ou la clé figure dans `disabledSlots` |
| rien du tout, aucun message | le client a le mod et le serveur ne l'a pas : le payload est jeté par le serveur comme canal inconnu |
| le message arrive, le modèle ne change pas | le mixin de rendu ne s'est pas appliqué. Pour les trinkets, c'est silencieux par construction, voir [troubleshooting.fr.md](troubleshooting.fr.md) |

La deuxième ligne est celle qui piège presque tout le monde : sur une installation neuve,
appuyer sur `=` sur un casque ne fait rien, parce que `enableVanillaArmor` vaut `false` par
défaut.

Un refus laisse quand même une trace, mais seulement dans le log **serveur** :

```text
[CheeseVanity] Slot 'helmet' not toggleable (enableVanillaArmor=false, enableTrinkets=true, isVanillaArmor=true)
```

---

## Lire la slot key d'un slot

Les slot keys ne sont documentées nulle part en jeu, et les groupes et noms Trinkets
dépendent des mods que ton pack installe. Le mod les affiche, au niveau `INFO`, dans le log
**client**, à chaque appui sur `=` au-dessus d'un slot :

```text
[CheeseVanity] Key '=' on slot: class=TrinketSlot, index=0, id=46, inv=TrinketInventory, hasStack=true
[CheeseVanity] Resolved slotKey: chest/back
```

Cette seconde ligne donne la chaîne exacte à mettre dans `disabledSlots`. Si
`Resolved slotKey` vaut `null`, le slot n'est pas pris en charge par ce mod et aucune
modification de configuration ne le rendra basculable.

⚠️ Ces deux lignes sont du log de débogage resté en `INFO` au lieu de `DEBUG`. Sur un
client chargé, c'est du bruit, et sur un serveur qui agrège les logs clients, c'est
beaucoup de bruit. Elles sont inoffensives, mais c'est une verrue ; voir
[architecture.fr.md](architecture.fr.md#faiblesses-connues).

---

## Aucun autre point d'entrée

Contrairement au reste du dépôt, ce mod n'offre pas de seconde route vers la même action :

- **aucune commande**, comme dit plus haut
- **aucun module CheeseDash.** CheeseVanity ne déclare pas d'entrypoint `cheesedash` dans
  son `fabric.mod.json`, donc il n'apparaît jamais dans la barre latérale du dashboard et
  n'expose aucune route REST. Il n'y a pas de page `dashboard.fr.md` pour ce mod parce
  qu'il n'y a rien à documenter.
- **aucune interface propre.** La seule interface est la surcouche rouge dessinée sur des
  slots qui existent déjà.

Un administrateur qui doit réinitialiser les préférences d'un joueur édite
`config/cheesevanity_data.json` serveur arrêté. Il n'existe aucun moyen de le faire en jeu,
et aucun rechargement à chaud : le fichier est lu une seule fois au démarrage.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
