package com.roicheese.eggandcheese.network;

/**
 * Legacy stub — egg creation now happens automatically on block placement.
 * Kept to avoid breaking AdminCommand references.
 */
public class PacketExtras {
    public static void register() {
        // No-op: creation flow moved to AdminCollectibleItem.postPlacement()
    }
}
