[← CheesePurse](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, tu auras un serveur qui fait tourner CheesePurse, un client qui
dessine le bouton, et un joueur dont les pièces de bronze quittent la barre d'action
rapide toutes seules pour atterrir dans la bourse Numismatic Overhaul, notification dans
la barre d'action à l'appui. Compte une dizaine de minutes, dont la plus grande partie
pour Gradle.

La marche à suivre part d'un serveur Fabric 1.21.1 vierge et d'un client Fabric 1.21.1.
Aucun autre mod de ce dépôt n'intervient.

---

## 1. Mettre Numismatic Overhaul en place d'abord

Télécharge [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) pour
1.21.1, ainsi que ce qu'il liste comme ses propres dépendances — à l'heure où ces lignes
sont écrites, Cardinal Components et owo-lib. Place le tout dans le `mods/` du serveur
et dans le `mods/` de ton client, à côté de Fabric API.

Démarre le serveur et connecte-toi une fois, avant de toucher à CheesePurse. Appuie sur
E et regarde en bas à droite de l'écran d'inventaire : Numismatic y affiche l'état de la
bourse. Cela confirme que la dépendance fonctionne seule.

Faire ça en premier compte. Si quelque chose cloche dans l'installation de Numismatic,
autant le découvrir maintenant plutôt qu'en accusant un addon de 260 lignes.

## 2. Construire CheesePurse

```bash
cd CheesePurse
./gradlew build
```

La construction résout Numismatic depuis le dépôt Maven de Modrinth : la première
exécution a donc besoin d'une connexion réseau. À la fin :

```text
CheesePurse/build/libs/numismatic-cheesepurse-1.0.0+1.21.1.jar
```

Voilà le fichier. Le `-sources.jar` à côté n'est pas un mod.

Si Gradle échoue sur un `maven.modrinth:numismatic-overhaul` non résolu, voir
[dépannage](troubleshooting.fr.md#la-construction-échoue-sur-mavenmodrinthnumismatic-overhaul).

## 3. L'installer des deux côtés

Arrête le serveur. Copie le jar dans :

- le `mods/` du serveur
- le `mods/` de ton client

Les deux. La moitié serveur fait tourner la boucle de dépôt et la moitié client dessine
le bouton ; aucune des deux ne sert à quoi que ce soit seule.

Démarre le serveur. Le log affiche :

```text
[NumismaticCheesePurse] Initializing…
[NumismaticCheesePurse] Ready.
```

Deux lignes, qui se suivent. Si le serveur s'arrête à la place sur un message de
dépendance `numismatic-overhaul` manquante, l'étape 1 n'a pas vraiment eu lieu côté
serveur.

## 4. Trouver le bouton

Rejoins le serveur et appuie sur E.

Une icône de pièce de 12×12 apparaît près du coin supérieur gauche de la fenêtre
d'inventaire, juste au-dessus du modèle du personnage, en `guiLeft + 27, guiTop + 9`.
Elle est grise.

Survole-la :

```text
Cheesepurse : DÉSACTIVÉE
Cliquez pour déposer automatiquement les pièces dans votre bourse.
```

Sur un client en anglais, la même infobulle affiche :

```text
Cheesepurse: OFF
Click to automatically deposit picked-up coins into your purse.
```

Aucune icône : le client n'a pas le jar, ou le mixin ne s'est pas appliqué.
[Quoi vérifier](troubleshooting.fr.md#il-ny-a-aucun-bouton-dans-mon-écran-dinventaire).

## 5. Se procurer des pièces, l'interrupteur encore désactivé

Donne-toi de quoi travailler :

```mcfunction
/give @s numismatic-overhaul:bronze_coin 64
```

64 pièces de bronze arrivent dans ton inventaire et y restent. L'interrupteur est
désactivé, rien n'y touche. Laisse l'inventaire ouvert et observe quelques secondes pour
en être sûr : c'est ton cas témoin.

## 6. Activer

Clique sur l'icône de pièce.

Trois choses se produisent, dans cet ordre :

1. La texture devient la pièce dorée.
2. L'infobulle passe à `Cheesepurse : ACTIVÉE` / `Cheesepurse: ON`.
3. Le client envoie un `ToggleAutoCollectPayload(true)` sur le canal
   `numismatic-cheesepurse:toggle_auto_collect`, et le serveur ajoute ton UUID à son
   ensemble.

En moins d'une demi-seconde — dix ticks serveur au maximum — les 64 pièces de bronze
disparaissent de ton inventaire et Numismatic place une notification dans ta barre
d'action :

```text
+ [64 Bronze]
```

L'affichage de ta bourse, en bas à droite de l'écran d'inventaire, monte de 64.

Le délai est précisément ce qu'il faut comprendre ici : le balayage n'est pas déclenché
par un ramassage. C'est une minuterie. Voir [la boucle de collecte](auto-collect.fr.md#la-boucle).

## 7. Vérifier sur un vrai ramassage

Avec le bouton activé, un `/give` suivi d'un lancer ne marche pas : les pièces données sont
balayées dans la bourse en moins d'une demi-seconde, avant que tu puisses les jeter. Pose
plutôt les pièces directement au sol, à quelques blocs :

```mcfunction
/summon item ~ ~ ~3 {Item:{id:"numismatic-overhaul:silver_coin",count:10}}
```

Avance dans la pile. Les pièces entrent normalement dans ton inventaire — ramassage
vanilla, auquel CheesePurse ne touche pas — et repartent au balayage suivant, avec
`+ [10 Silver]` dans la barre d'action.

Si tu es assez rapide avec l'inventaire ouvert, tu peux voir la pile occuper un
emplacement pendant un tick ou deux avant de partir. Cette fenêtre est réelle et dure
jusqu'à une demi-seconde.

## 8. Regarder le serveur voir la scène

Monte à `DEBUG` le niveau du logger `numismatic-cheesepurse` — le mod le crée avec
`LoggerFactory.getLogger(MOD_ID)`, il porte donc le nom de l'identifiant du mod, pas celui
du package — et le serveur raconte chaque étape :

```text
[NumismaticCheesePurse] Auto-collect ENABLED for Steve
[NumismaticCheesePurse] Collected 64 bronze units for Steve
[NumismaticCheesePurse] Collected 1000 bronze units for Steve
```

Les nombres sont en unités de bronze, la dénomination de base de Numismatic : 1 pour le
bronze, 100 par argent, 10 000 par or. Dix pièces d'argent se journalisent en `1000`,
pas en `10`.

## 9. Désactiver, et comprendre ce que « désactivé » veut dire

Clique à nouveau sur l'icône. La texture redevient grise, l'infobulle revient à
`DÉSACTIVÉE`, et le client envoie `ToggleAutoCollectPayload(false)`. Le serveur retire
ton UUID et la boucle t'ignore dès le tick suivant.

Les pièces déjà dans la bourse y restent. Rien n'est remboursé et rien n'est réversible
par la bascule — refais-toi un `/give` si tu veux retrouver ton cas témoin.

Une chose à essayer avant de passer à la suite, parce que c'est le seul comportement qui
prend les joueurs en traître. Active l'interrupteur, ouvre l'écran de bourse Numismatic
et retire des pièces. Elles apparaissent dans ton inventaire et repartent en moins d'une
demi-seconde, directement dans la bourse. La boucle ne sait pas d'où vient une pièce, et
s'en moque. C'est [le piège du retrait de bourse](auto-collect.fr.md#le-piège-du-retrait-de-bourse),
et le seul remède est de désactiver l'interrupteur avant de retirer.

---

## À lire ensuite

- [Dépôt automatique](auto-collect.fr.md) explique ce que la boucle considère comme une
  pièce, ce qui se passe quand un inventaire est plein, et pourquoi l'interrupteur se
  réinitialise au redémarrage.
- [Configuration](configuration.fr.md) traite du fait qu'il n'y a rien à configurer et
  de ce qu'il faudrait modifier à la place.

---

← [Installation](installation.fr.md) · [Dépôt automatique](auto-collect.fr.md) →
