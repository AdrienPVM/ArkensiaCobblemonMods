package com.roicheese.eggandcheese.texture;

/**
 * Splitmix32 RNG — matches the JS SeededRandom for identical sequences.
 * Used by ProceduralTextureGenerator to produce textures that exactly match
 * the CheeseDash web previewer.
 */
public class SeededRandom {
    private int state;

    public SeededRandom(long seed) {
        this.state = (int) seed;
    }

    private int _next() {
        state += 0x9e3779b9;
        int z = state;
        z = (z ^ (z >>> 16)) * 0x85ebca6b;
        z = (z ^ (z >>> 13)) * 0xc2b2ae35;
        return z ^ (z >>> 16);
    }

    public int nextInt(int bound) {
        return Integer.remainderUnsigned(_next(), bound);
    }

    public float nextFloat() {
        return Integer.toUnsignedLong(_next()) / 4294967296f;
    }

    public boolean nextBoolean() {
        return (_next() & 1) == 1;
    }
}
