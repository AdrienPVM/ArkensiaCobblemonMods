# Auto Coin Purse

Un mod Fabric pour Minecraft 1.21.1 qui fonctionne avec **Numismatic Overhaul**.

## Fonctionnement

Ouvre ton inventaire (`E`) — tu trouveras un petit bouton rond dans le **coin supérieur gauche** de l'écran :

| État | Apparence | Comportement |
|------|-----------|--------------|
| **OFF** | Cercle gris | Comportement normal, les pièces restent dans l'inventaire |
| **ON**  | Cercle doré + point vert | Toute pièce ramassée est automatiquement déposée dans ta bourse globale |

Le toggle est **persistant côté serveur** pour la session. Le client mémorise l'état localement (il re-sync au rechargement si besoin).

---

## Architecture du mod

```
AutoCoinPurse (serveur)
├── Enregistre le packet C2S (toggle)
├── Reçoit le packet → met à jour AUTO_COLLECT_PLAYERS (Set<UUID>)
└── ServerTickEvents.END_SERVER_TICK (toutes les 10 ticks)
    └── Pour chaque joueur avec toggle ON :
        ├── CurrencyHelper.getMoneyInInventory(player, true)   ← retire les pièces
        └── ModComponents.CURRENCY.get(player).modify(value)  ← ajoute à la bourse

AutoCoinPurseClient (client)
├── Enregistre le packet C2S côté client
└── InventoryScreenMixin.init() → ajoute AutoCollectButton

AutoCollectButton (widget Minecraft)
├── Render : drawTexture (gold circle / grey circle)
├── onClick : toggle AutoCoinPurseClient.autoCollectEnabled
└── Envoie ToggleAutoCollectPayload au serveur
```

---

## Build

### Prérequis
- Java 21
- Gradle (wrapper inclus)

### Compilation
```bash
./gradlew build
```

Le JAR sera dans `build/libs/autocoinpurse-1.0.0.jar`.

### Installation
1. Place le JAR dans ton dossier `.minecraft/mods/`
2. Assure-toi d'avoir **Fabric Loader 0.17.2+**, **Fabric API**, et **Numismatic Overhaul** installés.

---

## Personnaliser les textures du bouton

Les deux textures se trouvent dans :
```
src/main/resources/assets/autocoinpurse/textures/gui/
├── auto_collect_on.png    ← état activé  (20×20 px)
└── auto_collect_off.png   ← état désactivé (20×20 px)
```

Replace-les par tes propres icônes (même nom, même taille) pour personnaliser l'apparence.

---

## Persistance avancée (amélioration future)

Actuellement, le toggle est **en mémoire côté serveur** — il est réinitialisé si le serveur redémarre.

Pour une persistance complète, tu pourrais :
1. Utiliser les **Fabric AttachmentTypes** (`EntityDataAttachment`) pour sauvegarder l'état dans les données du joueur
2. Ou utiliser **Cardinal Components** (comme le fait Numismatic Overhaul lui-même avec `ModComponents.CURRENCY`)

Ces approches sont documentées dans la [Fabric wiki](https://fabricmc.net/).

---

## Dépendances

| Mod | Version |
|-----|---------|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.17.2 |
| Fabric API | ≥ 0.110.0+1.21.1 |
| Numismatic Overhaul | 0.3.4.1 (1.21) |
