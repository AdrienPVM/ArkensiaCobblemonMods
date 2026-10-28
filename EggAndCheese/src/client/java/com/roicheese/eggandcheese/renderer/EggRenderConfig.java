package com.roicheese.eggandcheese.renderer;

/**
 * Client-side config for egg rendering distance, synced from server.
 * Updated when the client receives a registry sync packet.
 */
public class EggRenderConfig {
    /** Blocks distance beyond which full voxel textures are replaced by a simple LOD box.
     *  0 = always show full detail (no LOD). Default = 10. */
    public static int textureRenderDistance = 20;
}
