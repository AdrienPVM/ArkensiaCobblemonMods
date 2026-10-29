[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

La plupart des problèmes de CheeseKart sont l'une de trois choses déguisées : le mod n'a
pas chargé, OpenBoatUtils n'est pas là où tu le crois, ou un preset n'est pas ce que tu
crois.

---

## Le serveur ne démarre pas, et le log parle de `cheesedash`

**Cause.** CheeseKart déclare `cheesedash` comme dépendance stricte. Fabric refuse de
démarrer sans elle plutôt que de charger un mod qui échouerait à sa première requête.

**Correctif.** Mets aussi le jar CheeseDash dans `mods/`. Les deux jars, toujours.

## `/cheesekart` n'existe pas

**Cause, par ordre de probabilité.** Le jar n'est pas dans `mods/` ; le jar est un
`-sources.jar` ; le serveur n'est pas en 1.21.1 ; Fabric API est absent.

**Correctif.** Cherche `[CheeseKart] Initialized` dans le log. Si la ligne est absente, le
mod n'a jamais chargé, et la raison se trouve plus haut dans le log — Fabric nomme les
mods qu'il rejette pendant le démarrage. Si la ligne est présente, la commande existe et
c'est au problème suivant que tu te heurtes.

## `/cheesekart` existe mais répond *Unknown or incomplete command*

**Cause.** L'arbre de commandes entier exige le niveau de permission 2. Brigadier cache
les sous-commandes que l'appelant ne peut pas exécuter, donc un joueur insuffisamment
autorisé voit l'erreur vanilla de commande inconnue plutôt qu'une erreur de permission.

**Correctif.** `/op <player>`, ou le niveau 2 via ton mod de permissions. Il n'existe
aucun moyen d'abaisser l'exigence sans modifier `CheeseKartCommand`.

## `/cheesekart presets` répond *No presets configured*

**Cause.** La table des presets en mémoire est vide, ce qui devrait être impossible après
un démarrage normal — le mod sème trois valeurs par défaut dès qu'il ne charge rien. Cela
signifie donc que les presets ont été supprimés *après* le démarrage, par l'API ou par un
`DELETE` sur chacun.

**Correctif.** Crées-en un, depuis le dashboard ou par
[`POST /presets`](dashboard.fr.md#post-presets). Ou bien arrête le serveur, supprime
`config/cheesekart.json`, et redémarre pour récupérer les trois presets fournis.

Si c'est arrivé au démarrage, cherche plutôt :

```text
[CheeseKart] Failed to load config: <reason>
```

Cela signifie que le JSON est mal formé (ou qu'un preset a `"id": null`). Le mod le
consigne et continue avec ce qu'il a lu avant l'erreur. Un démarrage ne peut toutefois
jamais aboutir à *No presets configured* : tout chargement qui se termine avec zéro preset
— une erreur sur la première entrée, pas de tableau `presets`, des entrées toutes sans `id`
— sème les trois valeurs par défaut et les sauvegarde par-dessus le fichier, qui est donc
perdu. Garde une copie avant de l'éditer à la main.

---

## Le kart est un bateau visible et se conduit comme un bateau

**Cause.** Deux mods client, deux moitiés du symptôme. *Se conduit comme un bateau* :
OpenBoatUtils est absent **sur le client de ce joueur**. *Visible* : CheeseKart est absent
de ce client — l'attribut invisible n'a aucun effet sur un bateau, et seul le mixin de rendu
client de CheeseKart le cache. Pour la moitié physique, le serveur vérifie si
le client a enregistré le canal et s'abstient complètement d'envoyer la physique sinon.
Ce n'est pas consigné, par choix — sinon chaque joueur sans OBU polluerait le log à chaque
lancement.

**Correctif.** Installe OpenBoatUtils — et CheeseKart — côté client. Si tu ne maîtrises pas les clients,
traite la course en kart comme facultative et dis aux joueurs ce qu'il leur faut.

**Comment confirmer.** Lance un kart pour un client dont tu sais qu'il a OBU. Si celui-là
se conduit correctement, le mod va bien et le problème est propre à chaque client.

## Personne n'a de physique, même avec OpenBoatUtils installé

**Cause.** La réflexion côté serveur vers la classe de payload d'OBU a échoué. Cela se
produit quand OBU est installé sur le serveur et qu'il a changé la forme de
`dev.o7moon.openboatutils.OpenBoatUtils$BytePayload`.

**Correctif.** Cherche dans le log de démarrage :

```text
[CheeseKart] Failed to init OBU reflection, OBU packets will not work
```

Il n'y a pas de correctif par configuration — le nom de la classe est codé en dur dans
`OpenBoatUtilsProxy.register()`. Soit tu reviens à une version d'OBU dont la classe
correspond, soit tu **retires OBU du serveur** pour ne le garder que sur les clients :
avec OBU absent côté serveur, CheeseKart enregistre son propre payload sur le canal et
envoie exactement les mêmes octets, en contournant complètement la réflexion. C'est
souvent le correctif le plus rapide.

## La physique est fausse sur un point précis

**Cause.** Presque toujours le preset, pas le mod.

**Correctif.** `/cheesekart presets` affiche les trois valeurs les plus souvent en cause.
Pour le reste, relis le preset avec `GET /presets/{id}` et compare avec
[la référence des champs](configuration.fr.md#champs-dun-preset). Les surprises
courantes :

| Symptôme | Champ probable |
|---|---|
| le kart accélère tout seul, glisse partout | `slipperiness` au-dessus de 1 |
| aucune vitesse de pointe, il gagne sans cesse | `accelStacking` est à `true` |
| impossible de sauter | `jumpForce` est à `0.0`, sa valeur par défaut |
| impossible de diriger en l'air | `airControl` est à `false` |
| des pilotes meurent à l'atterrissage | `fallDamage` est à `true` |
| le kart refuse de franchir une marche qu'il devrait passer | `stepHeight` sous la hauteur du bloc |
| le kart monte en continu | `gravity` est positive — le négatif descend |

## Une modification de preset n'a rien changé

**Cause.** L'une de deux choses. Soit tu as édité `config/cheesekart.json` à la main sur
un serveur en marche — le fichier est lu une seule fois au démarrage et ton édition sera
écrasée par la prochaine sauvegarde — soit tu as modifié un preset pendant que des
sessions tournaient en t'attendant à ce que les sessions *en cours* changent sans
réapplication.

**Correctif.** Édite les presets depuis le dashboard ou l'API, la seule voie qui écrive
dans la table vivante. Un `PUT` réapplique automatiquement ; vérifie le log :

```text
[CheeseKart] Re-applied preset 'drift' to 3 active session(s)
```

Si cette ligne manque, aucune session n'utilisait le preset — y compris le cas où tu as
modifié un preset dont tu as deviné le nom de travers et récolté un `404`.

Note que `locked` sur une session en cours ne change jamais, parce qu'une session le
photographie à son lancement. Arrête cette session et relance-la.

---

## Une commande `start` répond *Failed to start kart*

**Cause.** La montée forcée a été refusée. C'est rare : `startRiding(boat, true)` saute les
vérifications habituelles (« cette entité peut-elle monter sur celle-là ? ») et, si le
joueur chevauche déjà un cheval, un vrai bateau ou un wagonnet, l'en fait descendre avant
de le monter — un véhicule déjà occupé n'est donc *pas* une cause. Un joueur déjà dans un
*kart* n'entre pas non plus dans ce cas : cette session est arrêtée d'abord, puis
remplacée. Restent les quelques refus incontournables de vanilla, qu'un
bateau tout juste créé ne déclenche pour ainsi dire jamais.

**Correctif.** Cherche `[CheeseKart] Failed to mount <name> on boat` dans le log et
réessaie. Aucun nettoyage n'est nécessaire : le bateau qui avait été créé
est jeté avant que l'échec ne soit signalé.

## Un pilote est bloqué après l'arrêt de son kart

**Cause.** C'est précisément ce que la téléportation finale du démontage sert à éviter :
descendre d'un bateau invisible qui tournait avec une gravité personnalisée peut laisser
au joueur une vélocité périmée. Voir ce symptôme signifie que le démontage n'est pas allé
au bout, généralement parce que le joueur s'est déconnecté en pleine opération.

**Correctif.** Fais-le se reconnecter ; la position se resynchronise. S'il est encore
bloqué à la reconnexion, `/tp` d'un bloc vers le haut.

## Shift ne fait pas sortir le joueur

**Cause.** Comportement voulu, si le preset est `locked` — la valeur par défaut des trois
presets fournis.

Mais aussi par accident quand il ne l'est *pas* : le mixin de descente côté client ne
vérifie que si le joueur est en kart, pas si la session est verrouillée. Un client qui a
CheeseKart installé ne peut donc pas non plus sortir d'un kart non verrouillé avec Shift.
Voir [la remarque sur le mixin](architecture.fr.md#les-deux-mixins-client).

**Correctif.** `/cheesekart stop <player>`. Si tu as besoin de karts vraiment libres,
sache que la version actuelle ne les livre pas aux clients qui ont le mod.

## Un bateau invisible est resté dans le monde

**Cause.** Le serveur s'est arrêté sans exécuter son démontage : un crash, un `kill -9`,
un hébergeur qui débranche. Un arrêt propre arrête toutes les sessions ; un arrêt sale ne
peut pas.

**Correctif.** Ce qui reste est une entité bateau ordinaire avec l'attribut
d'invisibilité. Place-toi à côté et lance `/kill @e[type=boat,distance=..10]`, qui
supprimera aussi les bateaux légitimes à portée : choisis bien ton emplacement. Rien
d'autre ne reste derrière : les sessions ne vivent qu'en mémoire et ne reviennent pas
après un redémarrage.

**Prévention.** Lance `/cheesekart stopall` avant toute maintenance planifiée.

---

## Le dashboard répond *CheeseKart not ready*

**Cause.** Une route a été appelée avant le déclenchement de l'événement de démarrage du
serveur, ou après son arrêt. Le module répond `503` plutôt que de lever une exception.

**Correctif.** Attends `[CheeseKart] Server started — N presets loaded`. Si le serveur est
en marche et que cela persiste, l'`INSTANCE` de CheeseKart est nulle, ce qui signifie que
l'initialisation du mod a levé une exception — cherche une trace de pile entre
`Initializing...` et la fin du démarrage.

## Le module `kart` est absent du dashboard

**Cause.** CheeseDash découvre les modules par l'entrypoint `cheesedash` au démarrage. Un
module absent signifie que CheeseKart a chargé après la découverte, ou n'a pas chargé du
tout.

**Correctif.** Cherche `kart` dans `GET /api/v1/modules`. Absent là aussi, vérifie que
CheeseKart est bien dans `mods/` et cherche ses lignes d'initialisation dans le log.

## `GET /active` est vide alors que des gens roulent manifestement

**Cause.** Tu parles à un autre serveur que celui sur lequel ils se trouvent — le plus
souvent une instance de test sur la même machine.

**Correctif.** Vérifie le port et l'hôte. `/cheesekart list` en jeu donne la réponse qui
fait foi.

---

## Signaler un bug

Inclus :

1. **Le bloc de démarrage** — chaque ligne `[CheeseKart]` à partir de `Initializing...`.
   Les lignes OBU et CobbleShift en particulier nous disent sur lequel des deux chemins
   d'envoi tu te trouves, et cela détermine la moitié des causes possibles.
2. **Le preset**, obtenu par `GET /presets/{id}` ou lu dans le fichier. Pas une
   description.
3. **Si OpenBoatUtils est sur le serveur, sur le client, ou sur les deux.** Ces trois
   configurations se comportent différemment, et la réponse ne se devine jamais à partir
   d'une description du symptôme.
4. **Ce que tu attendais et ce qui s'est passé**, séparément.

Les logs se trouvent dans `logs/latest.log`. Les lignes pertinentes portent l'étiquette
`[CheeseKart]` :

```bash
grep CheeseKart logs/latest.log
```

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).
Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CheeseKart](../README.fr.md) →
