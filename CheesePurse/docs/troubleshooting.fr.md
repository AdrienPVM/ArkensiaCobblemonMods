[← CheesePurse](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

---

## Le serveur refuse de démarrer et le log nomme `numismatic-overhaul`

**Cause.** `fabric.mod.json` déclare `"numismatic-overhaul": "*"` sous `depends`. Fabric
Loader résout les dépendances avant toute initialisation de mod, et une dépendance
stricte non satisfaite interrompt le lancement. Le message ressemble à ceci :

```text
Mod 'Numismatic Cheesepurse' (numismatic-cheesepurse) 1.0.0+1.21.1 requires
any version of mod numismatic-overhaul, which is missing!
```

**Remède.** Installe [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul)
pour 1.21.1, ainsi que ses propres dépendances — Cardinal Components et owo-lib à
l'heure où ces lignes sont écrites. Les dépendances manquantes de Numismatic produisent
la même classe de message, en les nommant elles.

Cela vaut aussi côté client. CheesePurse est en `"environment": "*"` : un client sans
Numismatic ne se lancera pas non plus.

---

## Il n'y a aucun bouton dans mon écran d'inventaire

**Cause la plus fréquente.** Le jar est sur le serveur mais pas sur le client. Le bouton
vient d'un mixin client ; une installation serveur seule donne une boucle de dépôt
fonctionnelle que personne ne peut allumer.

**Remède.** Place aussi `numismatic-cheesepurse-1.0.0+1.21.1.jar` dans le `mods/` du
client, et vérifie qu'il apparaît dans la liste des mods sous le nom
**Numismatic Cheesepurse**. Si tu le cherches sous « CheesePurse », tu ne le trouveras
pas — voir [les trois noms](installation.fr.md#les-trois-noms-de-ce-mod).

**Deuxième cause.** Le mixin ne s'est pas appliqué. Cela n'échoue pas en silence : la
configuration des mixins est en `"required": true` avec `defaultRequire: 1`, donc une
injection ratée fait planter le client au démarrage avec un `MixinApplyError` nommant
`InventoryScreenMixin`. Si ton client tourne, c'est que le mixin s'est appliqué.

**Troisième cause.** Tu regardes le mauvais écran. Le mixin cible `InventoryScreen`,
l'inventaire survie ouvert par E. L'inventaire créatif est une autre classe et n'a pas de
bouton.

**Quatrième cause.** Un pack de ressources qui change les dimensions de l'interface
d'inventaire. Le mixin calcule la position du bouton à partir d'un 176×166 codé en dur :
avec une texture d'inventaire de taille inhabituelle, le bouton peut atterrir hors du
panneau visible. Désactive le pack pour confirmer.

---

## Le bouton est gris mais mes pièces disparaissent quand même

C'est celui qui génère des tickets de support. Lis
[le modèle](auto-collect.fr.md#le-modèle) une fois et le mystère s'évapore.

**Cause.** Le booléen client et l'ensemble serveur sont deux états distincts,
synchronisés seulement du client vers le serveur, et seulement au clic. Le serveur ne
dit jamais à un client ce qu'il croit. La séquence courante est :

1. Le joueur active l'interrupteur. Le serveur ajoute son UUID ; le booléen client passe
   à `true`.
2. Le joueur quitte. **Le serveur ne retire pas l'UUID** — rien ne gère la déconnexion.
3. Le joueur redémarre son client Minecraft. Le booléen statique est reconstruit à
   `false`.
4. Il se reconnecte. Le bouton s'affiche en gris ; le serveur continue de balayer son
   inventaire.

**Remède.** Clique deux fois sur le bouton — activer, puis désactiver. Le premier clic
envoie `ToggleAutoCollectPayload(true)`, le second envoie `false`, et le serveur finit
dans l'état qu'affiche le bouton. Un redémarrage de serveur vide aussi tous les UUID.

**Pourquoi ce n'est pas corrigé dans le code.** Il n'existe aucun paquet S2C dans ce
mod : le serveur n'a donc aucun moyen de corriger un client. En ajouter un est le premier
élément des [points d'extension](architecture.fr.md#points-dextension).

---

## Le bouton est doré mais rien n'est collecté

**Cause la plus probable.** Le symptôme miroir du précédent : le client se croit actif,
le serveur n'a pas l'UUID. Cela arrive après un redémarrage **du serveur**, puisque
`AUTO_COLLECT_PLAYERS` est reconstruit vide, alors que le client garde son booléen tant
que le jeu tourne.

**Remède.** Clique deux fois. Le même remède, pour la même raison.

**Deuxième cause.** Il n'y a rien à collecter. Le balayage sort tout de suite sur
`totalValue <= 0` : un joueur sans `CurrencyItem` dans son inventaire ne produit ni ligne
de log ni notification. Vérifie avec :

```mcfunction
/give @s numismatic-overhaul:bronze_coin 16
```

Seize bronze doivent disparaître en moins d'une demi-seconde.

**Troisième cause.** La notification est coupée, donc le dépôt est invisible. Si le
`moneyMessageLocation` de Numismatic vaut `DISABLED` sur ce client, les pièces quittent
l'inventaire sans aucun retour. Regarde plutôt l'affichage de la bourse en bas à droite
de l'écran d'inventaire : c'est le chiffre qui fait foi.

---

## Les pièces que je retire de ma bourse y retournent aussitôt

**Cause.** Conforme à ce qui est écrit. Le balayage scrute l'inventaire et dépose chaque
`CurrencyItem` qu'il y trouve, sans aucune notion de provenance. Retirer de la bourse met
des pièces dans l'inventaire ; le balayage suivant les y remet.

**Remède.** Désactive l'interrupteur avant de retirer. Explication complète dans
[le piège du retrait de bourse](auto-collect.fr.md#le-piège-du-retrait-de-bourse).

Rien n'est perdu dans l'aller-retour — les deux opérations conservent la valeur — donc
c'est agaçant plutôt que dangereux.

---

## Mes bourses d'argent ont disparu dans ma bourse personnelle

**Cause.** `MoneyBagItem` implémente `CurrencyItem`, et le scan teste l'interface plutôt
que d'énumérer des items. Une bourse d'argent dans l'inventaire est balayée comme des
pièces en vrac et dissoute dans le solde.

**Remède.** Désactive l'interrupteur avant d'en ramasser une, ou range les bourses
d'argent dans un conteneur : les coffres de l'Ender, les boîtes de Shulker et les
coffres ordinaires ne sont pas scrutés. La valeur n'est pas perdue, elle est dans la
bourse et peut être retirée sous forme de pièces.

Si ton serveur distribue des bourses d'argent comme récompenses de quête destinées à être
échangées en tant qu'objets, cela mérite d'être annoncé aux joueurs à l'avance.

---

## Modifier le fichier de configuration ne change rien

**Cause.** Il n'y a pas de fichier de configuration. Rien dans le code source ne lit
`getConfigDir()`, et `config/cheesepurse.json` est un fichier que tu as créé, pas un
fichier que le mod a écrit.

**Remède.** Le comportement est fixé par des constantes de compilation. Voir
[configuration](configuration.fr.md) pour savoir quelle valeur se trouve sur quelle
ligne, et recompile après modification.

Si ce que tu veux vraiment, c'est arrêter le spam de `+ [3 Silver]`, ce réglage
appartient à Numismatic Overhaul : `moneyMessageLocation`, par client, dans ses propres
options de mod.

---

## Une commande est refusée, ou n'existe pas

**Cause.** CheesePurse n'enregistre aucune commande. Il n'y a ni `/cheesepurse`, ni
`/autocollect`, ni aucun contrôle côté opérateur. Une commande qui semble appartenir à ce
mod appartient à un autre.

**Remède.** Aucun disponible. S'il te faut un moyen pour un administrateur de basculer la
fonctionnalité d'un joueur, il faut l'écrire — voir
[les points d'extension](architecture.fr.md#points-dextension).

---

## Le module est absent du dashboard CheeseDash

**Cause.** CheesePurse n'a aucune intégration CheeseDash. Il ne déclare pas d'entrypoint
`cheesedash` dans `fabric.mod.json`, n'a pas de package `dashboard` et n'enregistre aucun
module. Il n'apparaîtra jamais dans `GET /api/v1/modules` ni dans la barre latérale du
dashboard.

**Remède.** Aucun n'est nécessaire, rien n'est cassé. Ce mod est autonome par
conception.

---

## La construction échoue sur `maven.modrinth:numismatic-overhaul`

**Cause.** `build.gradle` récupère Numismatic depuis le dépôt Maven de Modrinth à
`https://api.modrinth.com/maven`, épinglé sur l'identifiant de version `DPXG6kTJ`. Un
échec ici vient généralement d'une absence d'accès réseau, d'un proxy qui bloque
`api.modrinth.com`, ou de Gradle lancé en `--offline` avec un cache vide.

**Remède.** Construis une fois avec un accès réseau pour remplir le cache Gradle ; les
constructions suivantes fonctionnent hors ligne. Dans un environnement verrouillé,
recopie l'artefact dans un dépôt local et fais pointer le bloc `maven { }` dessus.

La dépendance est `modCompileOnly` : elle n'affecte que la compilation et n'est jamais
embarquée dans le jar produit.

---

## Les dépôts sont plus lents qu'une demi-seconde

**Cause.** `SCAN_INTERVAL_TICKS` compte des ticks, pas des millisecondes. À 20 TPS, dix
ticks font 0,5 s ; à 10 TPS, une seconde entière ; sur un serveur qui s'étrangle à
5 TPS, deux secondes.

**Remède.** Répare les TPS. Le balayage lui-même ne coûte presque rien — un
`Set.contains` par joueur connecté plus un parcours de 41 emplacements par joueur
inscrit — donc si le dépôt paraît lent, c'est le serveur qui est lent, et ce mod
rapporte le symptôme plutôt qu'il ne le cause.

---

## Signaler un bug

Inclus ceci, dans cet ordre :

1. **De quel côté est le problème.** Serveur seul, client seul, ou les deux. La plupart
   des symptômes de cette page sont unilatéraux et la réponse change du tout au tout.
2. **Le log concerné.** `logs/latest.log` côté serveur pour tout ce qui touche au
   chargement ou à la collecte ; `.minecraft/logs/latest.log` côté client pour le bouton
   et le mixin.
3. **Les deux lignes d'initialisation**, pour qu'il soit clair que le mod a chargé :

   ```text
   [NumismaticCheesePurse] Initializing…
   [NumismaticCheesePurse] Ready.
   ```

4. **Un extrait du balayage en niveau `DEBUG`**, si le problème porte sur des pièces qui
   bougent ou ne bougent pas. Monte le niveau du logger `numismatic-cheesepurse`
   (nommé d'après l'identifiant du mod, pas le package) et reproduis ; les lignes voulues
   sont :

   ```text
   [NumismaticCheesePurse] Auto-collect ENABLED for <player>
   [NumismaticCheesePurse] Collected <n> bronze units for <player>
   ```

   L'absence de ligne `Collected` alors que le bouton est doré constitue déjà le
   diagnostic : le serveur n'a pas l'UUID de ce joueur.

5. **Ta version de Numismatic Overhaul.** Ce mod compile contre la 0.3.4 et accepte
   n'importe quelle version à l'exécution ; un écart est donc plausible et mérite d'être
   écarté tôt.
6. **La liste des mods**, ou au moins l'indication de savoir si un autre mod touche à la
   monnaie Numismatic ou à l'écran d'inventaire.

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).
Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CheesePurse](../README.fr.md) →
