package com.example.autocoinpurse;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class AutoCoinPurseClient implements ClientModInitializer {

    /**
     * Client-side toggle state.
     * The mixin reads/writes this field, and it's synced to the server
     * whenever the player clicks the button.
     */
    public static boolean autoCollectEnabled = false;

    @Override
    public void onInitializeClient() {
        // Le payload C2S est déjà enregistré dans AutoCoinPurse (entrypoint main)
        // qui tourne sur client ET serveur — pas besoin de le ré-enregistrer ici.
        // Le faire deux fois provoque un crash au démarrage du client.
    }
}
