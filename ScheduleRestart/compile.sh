#!/bin/bash

echo "🔨 Compilation du mod Scheduled Restart..."

# Vérifier Java
if ! command -v java &> /dev/null; then
    echo "❌ Java n'est pas installé. Installe Java 21."
    exit 1
fi

# Compiler
./gradlew build

if [ $? -eq 0 ]; then
    echo "✅ Compilation réussie !"
    echo "📦 Le fichier .jar se trouve dans : build/libs/"
    ls -lh build/libs/*.jar
else
    echo "❌ Erreur de compilation. Vérifie les logs ci-dessus."
    exit 1
fi
