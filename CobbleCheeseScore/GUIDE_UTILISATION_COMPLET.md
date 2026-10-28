# 📘 Guide d'Utilisation Complet - CobbleCheeseScore

## 🎯 Qu'est-ce que CobbleCheeseScore ?

CobbleCheeseScore est un mod Fabric pour serveurs Cobblemon qui génère automatiquement **30+ tableaux de classement** avec:
- ✅ Images PNG individuelles (http://serveur:44310/pokedex.png)
- ✅ API JSON (http://serveur:44310/api/pokedex.json)
- ✅ Interface web interactive (http://serveur:44310/)
- ✅ Mise à jour automatique toutes les X minutes
- ✅ Couleurs customisables par tableau
- ✅ Système de cache performant

---

## 📋 Prérequis

### Serveur
- ✅ Minecraft 1.21.1
- ✅ Fabric Loader 0.16.0+
- ✅ Fabric API 0.103.0+
- ✅ Cobblemon (n'importe quelle version récente)
- ✅ Java 21

### Fichiers de Données Requis
Le mod lit automatiquement ces fichiers dans votre monde :
```
world/
├── pokedex/[uuid].nbt          ← Pokédex des joueurs
├── cobblemonplayerdata/[uuid].json  ← Stats Cobblemon
└── stats/[uuid].json           ← Stats Minecraft vanilla
```

---

## 📦 Installation

### Étape 1 : Téléchargement
Récupérez le fichier **CobbleCheeseScore-COMPLET-v1.0.zip**

### Étape 2 : Compilation

```bash
# Extraire le zip
unzip CobbleCheeseScore-COMPLET-v1.0.zip
cd CobbleCheeseScore

# Compiler (nécessite Java 21)
./gradlew build

# Ou sur Windows
gradlew.bat build
```

Le fichier JAR sera généré dans : `build/libs/cobblecheesescore-1.0.0.jar`

### Étape 3 : Installation sur le Serveur

1. **Copier le JAR** dans le dossier `/mods/` de votre serveur
2. **Démarrer le serveur** une première fois
3. Le mod va créer : `/config/cobblecheesescore.json`
4. **Arrêter le serveur**
5. **Éditer** `/config/cobblecheesescore.json` si nécessaire
6. **Redémarrer** le serveur

---

## ⚙️ Configuration

### Fichier : `/config/cobblecheesescore.json`

#### Options Globales

```json
{
  "port": 44310,                      // Port du serveur web
  "updateIntervalMinutes": 10,        // Fréquence de mise à jour
  "timeZone": "Europe/Paris",         // Fuseau horaire
  "autoDetectDataFolder": true,       // Détection auto du dossier world
  "manualDataFolderPath": "",         // Chemin manuel si auto échoue
  "defaultMaxPlayers": 30,            // Nombre max de joueurs affichés
  "defaultRowsPerColumn": 10,         // Lignes par colonne
  "showLastUpdate": true,             // Afficher "Dernière mise à jour"
  "lastUpdateText": "Dernière mise à jour :",
  
  "enableWebInterface": true,         // Activer http://ip:port/
  "enableJsonApi": true,              // Activer /api/*.json
  "enableDiscordWebhook": false,      // Notifications Discord
  "discordWebhookUrl": "",            // URL webhook Discord
  "enableHistory": true,              // Historique des scores
  "historyRetentionDays": 30          // Jours de rétention
}
```

#### Couleurs Par Défaut

```json
"defaultColors": {
  "background": "#141414",           // Fond de l'image
  "titleBackground": "#009B72",      // Fond du titre
  "titleText": "#FFFFFF",            // Texte du titre
  "text": "#FFFFFF",                 // Texte normal
  "footerText": "#FFFF00",           // Texte footer
  "firstPlaceBackground": "#ff715b", // Fond 1ère place
  "secondPlaceBackground": "#ff5b42",// Fond 2ème place
  "thirdPlaceBackground": "#ff2f0f"  // Fond 3ème place
}
```

#### Configuration d'un Tableau

Exemple : Tableau "Chasseur de Shiny" avec fond doré

```json
{
  "id": "shiny_hunter",              // ID unique
  "enabled": true,                   // Activer/désactiver
  "filename": "shiny.png",           // Nom du fichier PNG
  "title": "Chasseur de Shiny ✨",   // Titre affiché
  "description": "Shinies capturés", // Description
  "sortOrder": "desc",               // "desc" ou "asc"
  "maxPlayers": 30,                  // Override du nombre max
  "rowsPerColumn": 10,               // Override lignes/colonne
  "colors": {                        // Override couleurs
    "titleBackground": "#FFD700"     // Fond doré uniquement pour ce tableau
  }
}
```

### Activer/Désactiver un Tableau

Mettez simplement `"enabled": false` :

```json
{
  "id": "pvp_champion",
  "enabled": false,    // ← Ce tableau ne sera pas généré
  ...
}
```

---

## 🌐 Accès aux Tableaux

Une fois le serveur démarré, les tableaux sont accessibles à :

### 1. Images PNG Individuelles

Format : `http://VOTRE_IP:44310/FILENAME.png`

Exemples :
```
http://localhost:44310/pokedex.png
http://localhost:44310/shiny.png
http://localhost:44310/playtime.png
http://localhost:44310/type_water.png
http://localhost:44310/type_fire.png
... (30 fichiers au total)
```

### 2. API JSON

Format : `http://VOTRE_IP:44310/api/SCOREBOARD_ID.json`

Exemples :
```
http://localhost:44310/api/pokedex.json
http://localhost:44310/api/shiny.json
http://localhost:44310/api/all.json  ← Tous les tableaux
```

Réponse JSON :
```json
{
  "scoreboard": "pokedex_completed",
  "title": "Pokédex Complété",
  "description": "Nombre d'espèces capturées",
  "lastUpdate": "2025-02-14T15:30:00Z",
  "players": [
    {
      "rank": 1,
      "uuid": "5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e",
      "name": "PlayerName",
      "score": 906,
      "formattedScore": "906/1025 (88%)"
    }
  ]
}
```

### 3. Interface Web Interactive

URL : `http://VOTRE_IP:44310/`

Interface HTML avec :
- ✅ Onglets cliquables pour chaque tableau
- ✅ Auto-refresh toutes les 5 minutes
- ✅ Bouton rafraîchir manuel
- ✅ Design responsive

---

## 📊 Liste des 30 Tableaux Disponibles

### Tableaux Principaux (11)

| ID | Titre | Description | Format Score |
|----|-------|-------------|--------------|
| `pokedex_completed` | Pokédex Complété | Espèces capturées | 906/1025 (88%) |
| `shiny_hunter` | Chasseur de Shiny ✨ | Shinies capturés | 69 |
| `play_time` | Temps de Jeu Total ⏱️ | Heures jouées | 141.4h |
| `battle_elite` | Combattant Élite ⚔️ | Victoires totales | 443 |
| `trainer_tamer` | Dompteur de Dresseurs 🎖️ | Victoires vs Trainers | 347 |
| `distance_traveled` | Distance Parcourue 🗺️ | Kilomètres totaux | 829.7 km |
| `evolutionist` | Évolutionniste 🦋 | Évolutions effectuées | 225 |
| `pokeball_crafter` | Artisan Pokéball ⚙️ | Pokéballs craftées | 5964 |
| `pvp_champion` | Champion PvP 🏆 | Victoires PvP | 4 |
| `trader` | Trader 🤝 | Trades effectués | 56 |
| `survivor` | Survivant 💀 | Moins de morts (ASC) | 10 morts |

### Tableaux Types (18)

Chaque type Pokémon a son tableau avec couleur officielle :

| Type | ID | Couleur Officielle |
|------|----|--------------------|
| Normal | `type_normal` | - |
| Water 💧 | `type_water` | #6890F0 |
| Fire 🔥 | `type_fire` | #F08030 |
| Grass 🌿 | `type_grass` | #78C850 |
| Electric ⚡ | `type_electric` | #F8D030 |
| Ice ❄️ | `type_ice` | #98D8D8 |
| Fighting 🥊 | `type_fighting` | #C03028 |
| Poison ☠️ | `type_poison` | #A040A0 |
| Ground 🏔️ | `type_ground` | #E0C068 |
| Flying 🪽 | `type_flying` | #A890F0 |
| Psychic 🔮 | `type_psychic` | #F85888 |
| Bug 🐛 | `type_bug` | #A8B820 |
| Rock 🪨 | `type_rock` | #B8A038 |
| Ghost 👻 | `type_ghost` | #705898 |
| Dragon 🐉 | `type_dragon` | #7038F8 |
| Dark 🌑 | `type_dark` | #705848 |
| Steel ⚙️ | `type_steel` | #B8B8D0 |
| Fairy 🧚 | `type_fairy` | #EE99AC |

---

## 🔍 Diagnostic & Dépannage

### Problème : Aucune donnée affichée

**Vérifications** :
1. Les joueurs ont-ils joué sur le serveur avec Cobblemon activé ?
2. Les fichiers existent-ils ?
   ```bash
   ls world/pokedex/
   ls world/cobblemonplayerdata/
   ls world/stats/
   ```
3. Logs du serveur :
   ```
   [CobbleCheeseScore] Collected data for X players
   ```

**Solutions** :
- Si `autoDetectDataFolder: false`, configurez `manualDataFolderPath`
- Vérifiez les permissions de lecture des fichiers

### Problème : Images ne se mettent pas à jour

**Vérifications** :
1. Logs :
   ```
   [CobbleCheeseScore] Updating scoreboards...
   [CobbleCheeseScore] Scoreboards updated successfully
   ```
2. Intervalle configuré : `updateIntervalMinutes: 10`

**Solutions** :
- Rafraîchir le navigateur avec CTRL+F5 (vider le cache)
- Vérifier que le serveur est en cours d'exécution
- Augmenter/diminuer l'intervalle de mise à jour

### Problème : Port déjà utilisé

**Erreur** :
```
Failed to start web server: Address already in use
```

**Solution** :
Changer le port dans la config :
```json
{
  "port": 44311  // Au lieu de 44310
}
```

### Problème : Serveur web inaccessible

**Vérifications** :
1. Pare-feu : autoriser le port 44310
2. Test local : `curl http://localhost:44310/pokedex.png`
3. Test depuis l'extérieur : `curl http://IP_SERVEUR:44310/pokedex.png`

**Solutions** :
- Ouvrir le port dans le pare-feu serveur
- Vérifier les règles NAT/routeur si accès externe

### Problème : Certains joueurs manquent

**Cause** : Le joueur n'a pas de fichier Pokédex `.nbt`

**Solution** : Le joueur doit avoir capturé au moins 1 Pokémon

---

## 📈 Utilisation Avancée

### Intégration Discord (Webhook)

Activez les notifications quand le top 1 change :

```json
{
  "enableDiscordWebhook": true,
  "discordWebhookUrl": "https://discord.com/api/webhooks/VOTRE_WEBHOOK"
}
```

Le mod enverra :
```
🏆 **Nouveau Champion - Pokédex Complété!**
PlayerName a pris la 1ère place avec 906 espèces capturées!
```

### Affichage sur un Site Web

Intégrez les tableaux dans votre site :

```html
<img src="http://VOTRE_SERVEUR:44310/pokedex.png" alt="Pokédex">
<img src="http://VOTRE_SERVEUR:44310/shiny.png" alt="Shinies">
```

Ou utilisez l'iframe pour l'interface complète :
```html
<iframe src="http://VOTRE_SERVEUR:44310/" width="100%" height="800px"></iframe>
```

### API pour Applications Tierces

Exemple JavaScript :
```javascript
fetch('http://VOTRE_SERVEUR:44310/api/pokedex.json')
  .then(res => res.json())
  .then(data => {
    console.log('Top joueur:', data.players[0].name);
    console.log('Score:', data.players[0].formattedScore);
  });
```

### Personnalisation des Couleurs

Exemple : Tableau PvP en rouge intense

```json
{
  "id": "pvp_champion",
  "colors": {
    "titleBackground": "#C03028",
    "firstPlaceBackground": "#FF0000",
    "secondPlaceBackground": "#CC0000",
    "thirdPlaceBackground": "#990000"
  }
}
```

---

## 🚀 Performance & Optimisation

### Pour 30 Joueurs
- ⏱️ Mise à jour complète : ~1-2 secondes
- 💾 Mémoire utilisée : ~50-100 MB (cache + images)
- 🔄 Intervalle recommandé : 10 minutes

### Pour 100+ Joueurs
- ⏱️ Mise à jour complète : ~3-5 secondes
- 💾 Mémoire utilisée : ~100-200 MB
- 🔄 Intervalle recommandé : 15-20 minutes

### Optimisations
```json
{
  "updateIntervalMinutes": 15,  // Moins fréquent = moins de charge
  "defaultMaxPlayers": 20,       // Moins de joueurs = images plus petites
  "defaultRowsPerColumn": 10     // Ajuster pour la taille d'image
}
```

---

## 📝 Logs Importants

### Démarrage Réussi
```
[CobbleCheeseScore] Initializing CobbleCheeseScore...
[CobbleCheeseScore] Configuration loaded from /config/cobblecheesescore.json
[CobbleCheeseScore] Server started - Initializing scoreboards...
[CobbleCheeseScore] Auto-detected world path: /world
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: pokedex_completed (25 players)
[CobbleCheeseScore] Web server started on port 44310
[CobbleCheeseScore] Access scoreboards at: http://localhost:44310/
```

### Mise à Jour Périodique
```
[CobbleCheeseScore] Updating scoreboards...
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: pokedex_completed (25 players)
[CobbleCheeseScore] Updated scoreboard: shiny_hunter (28 players)
... (pour chaque tableau activé)
[CobbleCheeseScore] All scoreboards updated successfully
```

### Erreurs Communes
```
[CobbleCheeseScore] WARN: No player data found!
→ Aucun joueur n'a de données Pokédex

[CobbleCheeseScore] ERROR: Failed to start web server: Address already in use
→ Port déjà utilisé, changer le port dans la config

[CobbleCheeseScore] WARN: Image not ready for scoreboard: pokedex_completed
→ Attendre la première mise à jour
```

---

## ❓ FAQ

**Q: Les tableaux fonctionnent-ils en solo/local ?**
R: Oui, tant que Cobblemon est installé et que vous avez joué.

**Q: Puis-je ajouter mes propres tableaux ?**
R: Oui, éditez la config et ajoutez une nouvelle entrée dans `scoreboards`. Le calcul du score devra être ajouté dans `ScoreboardManager.java`.

**Q: Les données sont-elles sauvegardées ?**
R: Les scores sont recalculés à chaque mise à jour depuis les fichiers source. L'historique peut être activé avec `enableHistory: true`.

**Q: Compatibilité avec d'autres mods ?**
R: Compatible avec tous les mods Fabric. Aucun conflit connu.

**Q: Puis-je changer le port après installation ?**
R: Oui, éditez `/config/cobblecheesescore.json` et redémarrez.

**Q: Comment désactiver l'interface web ?**
R: `"enableWebInterface": false` dans la config.

**Q: Les PNG sont-ils publics ?**
R: Oui, accessibles à quiconque connaît l'URL. Pas d'authentification.

---

## 📞 Support & Contribution

### Bugs & Suggestions
Contactez l'administrateur du serveur Cacademy.

### Logs de Debug
Si problème, fournir :
1. Version Minecraft / Fabric / Cobblemon
2. Fichier `/config/cobblecheesescore.json`
3. Logs serveur (section `[CobbleCheeseScore]`)

---

## 📜 Licence

MIT License - Libre d'utilisation et modification.

---

## 🎉 Remerciements

Créé pour le serveur **Cacademy**.

**Bon classement ! 🏆**
