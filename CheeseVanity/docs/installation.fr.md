[← CheeseVanity](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CheeseVanity s'installe des **deux côtés**, et c'est l'un des rares mods du dépôt où ce
n'est pas une formalité. Le serveur possède la préférence et la persiste ; le client est
la seule chose capable d'agir dessus. Un côté sans l'autre te donne la moitié d'un mod :

| Où est le jar | Ce qui se passe |
|---|---|
| serveur et client | le mod fonctionne comme documenté |
| serveur seulement | la touche `=` vit dans un mixin client, donc aucun joueur ne peut envoyer de bascule : rien de nouveau n'est stocké, et rien n'est jamais masqué, sur aucun écran, pour personne |
| client seulement | appuyer sur `=` envoie un paquet que le serveur ne comprend pas et jette ; rien n'est stocké, rien n'est masqué |

Il déclare `"environment": "*"` dans `fabric.mod.json`, donc le même jar couvre les deux
côtés.

---

## Prérequis

| | |
|---|---|
| **Requis** | Minecraft 1.21.1 · Fabric Loader ≥ 0.16.0 · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 21 |
| **Optionnel** | [Trinkets](https://modrinth.com/mod/trinkets) 3.10.0 ou compatible |

Ces trois entrées requises sont le contenu littéral de `depends` dans `fabric.mod.json`.
`suggests` liste `trinkets` sans contrainte de version ; la compilation se fait contre
`dev.emi:trinkets:3.10.0`, déclaré `modCompileOnly`, donc Trinkets n'est **pas** embarqué
dans le jar et doit être installé séparément si tu veux les slots de trinkets.

Il n'y a pas de dépendance CheeseDash, et aucune dépendance vers un mod frère. Ce mod se
construit et tourne tout seul.

⚠️ Sans Trinkets et avec la configuration d'origine, le mod ne fait rien du tout :
`enableTrinkets` n'a rien sur quoi s'appliquer et `enableVanillaArmor` vaut `false`. Lis
[configuration.fr.md](configuration.fr.md#enablevanillaarmor) avant de conclure que
l'installation a échoué.

---

## Compilation

Aucun jar frère n'a besoin d'exister d'abord, donc il n'y a pas d'ordre de construction à
respecter :

```bash
cd CheeseVanity
./gradlew build
```

La sortie arrive dans `build/libs/` :

```text
cheesevanity-1.0.0+1.21.1.jar
cheesevanity-1.0.0+1.21.1-sources.jar
```

Le `+1.21.1` fait partie de `mod_version` dans `gradle.properties`, ce n'est pas Loom qui
l'ajoute. Déploie le premier fichier ; le jar de sources est fait pour être lu, pas pour
tourner.

`tools/build-all.sh`, à la racine du dépôt, construit tous les mods dans l'ordre des
dépendances et couvre celui-ci aussi.

---

## Déploiement

Copie `cheesevanity-1.0.0+1.21.1.jar` dans le `mods/` du serveur, et le même jar dans le
`mods/` de chaque client. Supprime d'abord tout `cheesevanity-*.jar` plus ancien : deux
versions du même identifiant de mod dans un seul dossier `mods/` empêchent Fabric Loader
de démarrer.

Il n'y a rien d'autre à faire. Pas de datapack, pas de pack de ressources, aucun registre à
synchroniser.

---

## Ce que crée le premier lancement

Sur le serveur, `VanityConfig.load()` ne trouve aucun fichier et en écrit un :

```text
config/cheesevanity.json
```

```json
{
  "enableVanillaArmor": false,
  "enableTrinkets": true,
  "disabledSlots": []
}
```

`config/cheesevanity_data.json` n'est **pas** créé au démarrage. Il n'apparaît que la
première fois qu'un joueur bascule quelque chose, parce que `VanityData.save()` est appelé
depuis le gestionnaire de bascule et nulle part ailleurs. Un `config/` qui ne contient
que `cheesevanity.json` après un premier lancement sans joueur est normal.

Un client écrit aussi `config/cheesevanity.json`, sous `.minecraft/config/`, parce que
l'entrypoint principal s'exécute aussi sur un client. Le modifier n'a aucun effet sur un
serveur multijoueur, mais c'est la configuration réellement utilisée en solo et sur un monde
LAN hébergé par ce client : le serveur intégré vit dans le même processus et la lit.

---

## Vérifier qu'il est chargé

Log serveur, pendant l'initialisation du mod :

```text
[CheeseVanity] Loaded — vanilla armor: false, trinkets: true
```

Les deux valeurs sont la configuration telle qu'elle a réellement été parsée, ce qui fait de
cette ligne le moyen le plus rapide de confirmer qu'une modification a bien pris.

Log client, pendant l'initialisation du client :

```text
[CheeseVanity] Client initialized
```

Puis vérifie en jeu, dans cet ordre, parce que chaque étape élimine une panne possible :

1. Ouvre ton inventaire et appuie sur `=` au-dessus d'un slot. Le log **client** doit
   afficher `[CheeseVanity] Key '=' on slot: ...` puis
   `[CheeseVanity] Resolved slotKey: ...`. Rien d'affiché signifie que le mixin ne s'est pas
   appliqué, ou que la touche ne lui est jamais parvenue.
2. Un `Resolved slotKey` différent de `null` signifie que le slot est compris. `null`
   signifie que ce slot n'est pas pris en charge par le mod.
3. Un message en barre d'action signifie que le serveur a reçu, accepté et stocké la
   bascule. Le silence ici, avec une clé non nulle, signifie que la configuration l'a
   refusée — cherche `not toggleable` dans le log serveur.
4. Une croix rouge sur le slot et la pièce absente en F5 signifient que le côté rendu
   fonctionne.

[troubleshooting.fr.md](troubleshooting.fr.md) est organisé autour de celle de ces quatre
étapes qui s'arrête.

---

## Désinstallation

Arrête le serveur, retire le jar du serveur et de chaque client, redémarre. Il n'y a rien à
migrer et aucune donnée de monde n'est touchée : le mod n'enregistre ni item, ni bloc, ni
entité, ni composant, ni objectif de scoreboard, et il n'écrit rien dans la sauvegarde.

Ce qui reste :

- `config/cheesevanity.json` — la configuration, inoffensive, supprime-la si tu veux
- `config/cheesevanity_data.json` — les slots masqués de chaque joueur, indexés par UUID

Garde le second fichier si une réinstallation est possible. Les préférences des joueurs
reviennent exactement comme elles étaient, parce que les clés sont des noms de slots et des
UUID, pas des références à quoi que ce soit qui ait pu devenir obsolète.

Retirer le mod rend immédiatement visible tout l'équipement. Rien n'avait jamais été
déséquipé, donc il n'y a aucun état d'inventaire à réparer.

---

← [CheeseVanity](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
