# Scheduled Restart - Mod Fabric pour Minecraft 1.21.1

## 📋 Description
Mod serveur Fabric qui permet de planifier des redémarrages ou arrêts avec countdown automatique.

## ⚙️ Fonctionnalités
- Planification de redémarrage/arrêt avec countdown
- Rappels toutes les minutes
- Countdown des 10 dernières secondes
- Messages avec émojis
- Réservé aux OPs (niveau permission 2)

## 🎮 Commandes

### Planifier un redémarrage
```
/schedulerestart <minutes>
```
Exemple : `/schedulerestart 5` → redémarre dans 5 minutes

### Planifier un arrêt
```
/schedulerestart <minutes> stop
```
Exemple : `/schedulerestart 10 stop` → arrête dans 10 minutes

## 🔨 Compilation

1. Assure-toi d'avoir Java 21 installé
2. Dans le dossier du mod, exécute :
```bash
./gradlew build
```
(Sur Windows : `gradlew.bat build`)

3. Le fichier `.jar` se trouve dans `build/libs/`

## 📦 Installation

1. Place le fichier `.jar` dans le dossier `mods/` de ton serveur
2. Redémarre le serveur
3. Vérifie que le mod est chargé avec `/mods` (si Fabric API est installé)

## ⚠️ Prérequis
- Minecraft 1.21.1
- Fabric Loader 0.16.9+
- Fabric API 0.107.0+

## 📝 Notes
- Le redémarrage utilise la commande `stop` - ton panel/wrapper (Minestrator, systemd, etc.) doit être configuré pour auto-restart
- Si tu veux un vrai arrêt sans redémarrage, utilise l'argument `stop`
- Une seule planification peut être active à la fois (la nouvelle annule l'ancienne)

## 🐛 En cas de problème
- Vérifie les logs du serveur dans `logs/latest.log`
- Assure-toi que Fabric API est bien installé
- Vérifie que tu as les permissions OP niveau 2 minimum
