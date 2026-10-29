[← CheeseVanity](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, tu auras masqué un trinket sur ton propre personnage, vu la pièce
disparaître en vue troisième personne, vérifié qu'un autre joueur voit la même chose, puis
tout remis en place. L'ensemble prend environ trois minutes.

Cette page est écrite pour les **joueurs**. Le reste du dépôt documente des outils
d'administration ; ce mod est l'exception, et les sections qui demandent un opérateur de
serveur sont signalées comme telles. Si tu es l'administrateur et que tu installes le mod
pour la première fois, fais l'étape 0 puis confie la suite à qui veut tester.

---

## 0. Pour l'administrateur : rendre quelque chose basculable

Saute cette étape si tu es joueur sur un serveur qui fait déjà tourner le mod.

La configuration d'origine autorise les slots Trinkets et interdit les armures vanilla. Si
ton pack a Trinkets, tu n'as rien à faire. S'il ne l'a pas, rien n'est basculable du tout et
le mod aura l'air cassé, donc modifie `config/cheesevanity.json` sur le serveur :

```json
{
  "enableVanillaArmor": true,
  "enableTrinkets": true,
  "disabledSlots": []
}
```

Redémarre le serveur. La configuration est lue une fois au démarrage et jamais rechargée.
Confirme depuis le log :

```text
[CheeseVanity] Loaded — vanilla armor: true, trinkets: true
```

Si la ligne affiche encore `false`, le fichier que tu as modifié n'est pas celui que le
serveur a lu — voir [le chemin de la configuration](configuration.fr.md).

---

## 1. Vérifie que le mod est sur ton client

Rejoins le serveur et ouvre ton inventaire. Rien ne change visuellement pour l'instant ; le
mod n'ajoute ni bouton ni onglet.

Le mod s'annonce dans le log plutôt que dans l'interface. Ouvre ton log client
(`.minecraft/logs/latest.log`) et cherche :

```text
[CheeseVanity] Client initialized
```

Si cette ligne manque, le jar n'est pas dans le `mods/` de ton client. Le masquage se fait
entièrement sur ton client : sans lui, rien de ce qui suit ne fonctionnera, aussi bien
configuré que soit le serveur.

---

## 2. Équipe quelque chose que tu veux masquer

Place un trinket dans un slot — une cape dans `chest/back`, un chapeau dans `head/hat`, une
bague, ce que ton pack propose. Si tu testes plutôt une armure, mets un casque.

Appuie une fois sur `F5` pour obtenir la vue par-dessus l'épaule de ton personnage (deux
fois retourne la caméra face à toi), et
regarde-le. C'est ton image de référence ; tu veux pouvoir faire la différence dans un
instant.

---

## 3. Découvre le nom du slot

Retourne dans ton inventaire, survole le slot qui contient l'item, et appuie sur `=`.

Ouvre ton log client. Deux lignes sont apparues :

```text
[CheeseVanity] Key '=' on slot: class=TrinketSlot, index=0, id=46, inv=TrinketInventory, hasStack=true
[CheeseVanity] Resolved slotKey: chest/back
```

Cette seconde valeur, `chest/back`, est la **slot key**. C'est le seul nom que le mod
emploie jamais pour un slot, et tu en auras besoin le jour où tu voudras demander à un
administrateur de bloquer ou de débloquer un slot précis.

Si `Resolved slotKey` affiche `null`, ce slot n'est pas pris en charge par le mod. Essaie un
autre. Si aucune ligne n'est apparue, va voir
[troubleshooting.fr.md](troubleshooting.fr.md#appuyer-sur--ne-fait-rien-et-le-log-client-reste-muet).

---

## 4. Lis la confirmation

Le même appui a déjà fait le travail. Regarde ta barre d'action, juste au-dessus de la barre
d'items :

```text
[CheeseVanity] chest/back hidden
```

en rouge. Le serveur a accepté la bascule, l'a enregistrée, et l'a annoncée à tous les
autres joueurs.

Le slot de ton écran d'inventaire porte maintenant un carré rouge translucide et une croix
rouge dessinés sur l'item. L'item est toujours là et toujours équipé. La croix signifie
« ceci est masqué sur mon modèle », pas « ceci est désactivé ».

Si tu n'as reçu aucun message ici alors que l'étape 3 a affiché une vraie slot key, le
serveur a refusé la bascule — presque toujours parce que la configuration n'autorise pas ce
genre de slot. L'étape 0 s'en occupe.

---

## 5. Regarde-toi

Ferme l'inventaire et regarde-toi de nouveau à la troisième personne (`F5` fait défiler
première personne, vue de dos, vue de face).

La cape a disparu. Ton personnage a l'air de ne rien porter dans ce slot. Regarde tes points
d'armure sur l'ATH si tu as masqué un plastron : ils n'ont pas bougé. L'équipement
fonctionne, il est seulement invisible.

C'est la partie que les gens comprennent de travers, donc disons-le explicitement : le
changement est visible **en troisième personne pour toi, et sur ton modèle pour tous les
autres joueurs qui ont le mod installé**. Ce n'est pas un aperçu privé. Un joueur qui se
connecte sans CheeseVanity dans son `mods/` voit encore ta cape, et rien ne peut y être fait
depuis le serveur. [Qui voit quoi](visibility.fr.md) explique la frontière en détail.

---

## 6. Fais confirmer par quelqu'un d'autre

Demande à un autre joueur qui a le mod de te regarder. Il voit la même chose que toi : pas
de cape.

Puis demande-lui de se déconnecter et de revenir. Il ne voit toujours pas de cape, parce que
le serveur envoie les slots masqués de chaque joueur à qui se connecte. C'est l'étape qui
prouve que la préférence est côté serveur et non une astuce locale.

---

## 7. Déconnecte-toi et reviens

Déconnecte-toi. Reconnecte-toi. Appuie sur `F5`.

Toujours masqué. Ta préférence a été écrite dans `config/cheesevanity_data.json` sur le
serveur à l'instant où tu as appuyé sur `=`, et elle est relue à chaque démarrage du
serveur. Elle survit à ta déconnexion, à un redémarrage du serveur et à un changement
d'item dans ce slot — mets une autre cape dans `chest/back` et elle est masquée aussi,
parce que la préférence appartient au slot.

Elle ne te suit **pas** sur un autre serveur. Chaque serveur garde son propre fichier.

---

## 8. Remets tout en place

Survole le même slot et appuie encore une fois sur `=` :

```text
[CheeseVanity] chest/back visible
```

en vert. La croix rouge a disparu, la cape est revenue sur ton modèle, et la clé a été
retirée de ton ensemble stocké. Un joueur sans aucun slot masqué est entièrement retiré du
fichier de données : tu ne laisses rien derrière toi.

---

## Pour aller plus loin

- [Qui voit quoi](visibility.fr.md) — le modèle de synchronisation et les limites exactes
  du masquage
- [Contrôles](commands.fr.md) — l'appui de touche documenté en entier, avec tous ses échecs
  silencieux
- [Configuration](configuration.fr.md) — pour l'administrateur : les trois clés, et comment
  protéger un slot comme un sac à dos pour qu'il ne puisse pas être masqué

---

← [Installation](installation.fr.md) · [Contrôles](commands.fr.md) →
