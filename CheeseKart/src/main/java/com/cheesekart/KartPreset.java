package com.cheesekart;

import com.google.gson.JsonObject;

public class KartPreset {
    private String id;
    private String name;
    private float slipperiness = 0.98f;
    private float stepHeight = 1.25f;
    private double gravity = -0.04;
    private float forwardAccel = 0.04f;
    private float backwardAccel = 0.005f;
    private float yawAccel = 1.0f;
    private float turnForwardAccel = 0.005f;
    private boolean accelStacking = false;
    private boolean airControl = true;
    private boolean airStepping = true;
    private float jumpForce = 0.0f;
    private boolean fallDamage = false;
    private boolean locked = true;
    private String species = "";

    public KartPreset() {}

    public KartPreset(String id, String name) {
        this.id = id;
        this.name = name;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public float getSlipperiness() { return slipperiness; }
    public void setSlipperiness(float v) { slipperiness = v; }
    public float getStepHeight() { return stepHeight; }
    public void setStepHeight(float v) { stepHeight = v; }
    public double getGravity() { return gravity; }
    public void setGravity(double v) { gravity = v; }
    public float getForwardAccel() { return forwardAccel; }
    public void setForwardAccel(float v) { forwardAccel = v; }
    public float getBackwardAccel() { return backwardAccel; }
    public void setBackwardAccel(float v) { backwardAccel = v; }
    public float getYawAccel() { return yawAccel; }
    public void setYawAccel(float v) { yawAccel = v; }
    public float getTurnForwardAccel() { return turnForwardAccel; }
    public void setTurnForwardAccel(float v) { turnForwardAccel = v; }
    public boolean isAccelStacking() { return accelStacking; }
    public void setAccelStacking(boolean v) { accelStacking = v; }
    public boolean isAirControl() { return airControl; }
    public void setAirControl(boolean v) { airControl = v; }
    public boolean isAirStepping() { return airStepping; }
    public void setAirStepping(boolean v) { airStepping = v; }
    public float getJumpForce() { return jumpForce; }
    public void setJumpForce(float v) { jumpForce = v; }
    public boolean isFallDamage() { return fallDamage; }
    public void setFallDamage(boolean v) { fallDamage = v; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean v) { locked = v; }
    public String getSpecies() { return species; }
    public void setSpecies(String v) { species = v == null ? "" : v; }

    // ── JSON ──────────────────────────────────────────────────────────────────
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("slipperiness", slipperiness);
        o.addProperty("stepHeight", stepHeight);
        o.addProperty("gravity", gravity);
        o.addProperty("forwardAccel", forwardAccel);
        o.addProperty("backwardAccel", backwardAccel);
        o.addProperty("yawAccel", yawAccel);
        o.addProperty("turnForwardAccel", turnForwardAccel);
        o.addProperty("accelStacking", accelStacking);
        o.addProperty("airControl", airControl);
        o.addProperty("airStepping", airStepping);
        o.addProperty("jumpForce", jumpForce);
        o.addProperty("fallDamage", fallDamage);
        o.addProperty("locked", locked);
        o.addProperty("species", species);
        return o;
    }

    public static KartPreset fromJson(JsonObject o) {
        KartPreset p = new KartPreset();
        if (o.has("id")) p.id = o.get("id").getAsString();
        if (o.has("name")) p.name = o.get("name").getAsString();
        if (o.has("slipperiness")) p.slipperiness = o.get("slipperiness").getAsFloat();
        if (o.has("stepHeight")) p.stepHeight = o.get("stepHeight").getAsFloat();
        if (o.has("gravity")) p.gravity = o.get("gravity").getAsDouble();
        if (o.has("forwardAccel")) p.forwardAccel = o.get("forwardAccel").getAsFloat();
        if (o.has("backwardAccel")) p.backwardAccel = o.get("backwardAccel").getAsFloat();
        if (o.has("yawAccel")) p.yawAccel = o.get("yawAccel").getAsFloat();
        if (o.has("turnForwardAccel")) p.turnForwardAccel = o.get("turnForwardAccel").getAsFloat();
        if (o.has("accelStacking")) p.accelStacking = o.get("accelStacking").getAsBoolean();
        if (o.has("airControl")) p.airControl = o.get("airControl").getAsBoolean();
        if (o.has("airStepping")) p.airStepping = o.get("airStepping").getAsBoolean();
        if (o.has("jumpForce")) p.jumpForce = o.get("jumpForce").getAsFloat();
        if (o.has("fallDamage")) p.fallDamage = o.get("fallDamage").getAsBoolean();
        if (o.has("locked")) p.locked = o.get("locked").getAsBoolean();
        if (o.has("species")) p.species = o.get("species").getAsString();
        return p;
    }

    public void mergeFrom(JsonObject o) {
        if (o.has("name")) name = o.get("name").getAsString();
        if (o.has("slipperiness")) slipperiness = o.get("slipperiness").getAsFloat();
        if (o.has("stepHeight")) stepHeight = o.get("stepHeight").getAsFloat();
        if (o.has("gravity")) gravity = o.get("gravity").getAsDouble();
        if (o.has("forwardAccel")) forwardAccel = o.get("forwardAccel").getAsFloat();
        if (o.has("backwardAccel")) backwardAccel = o.get("backwardAccel").getAsFloat();
        if (o.has("yawAccel")) yawAccel = o.get("yawAccel").getAsFloat();
        if (o.has("turnForwardAccel")) turnForwardAccel = o.get("turnForwardAccel").getAsFloat();
        if (o.has("accelStacking")) accelStacking = o.get("accelStacking").getAsBoolean();
        if (o.has("airControl")) airControl = o.get("airControl").getAsBoolean();
        if (o.has("airStepping")) airStepping = o.get("airStepping").getAsBoolean();
        if (o.has("jumpForce")) jumpForce = o.get("jumpForce").getAsFloat();
        if (o.has("fallDamage")) fallDamage = o.get("fallDamage").getAsBoolean();
        if (o.has("locked")) locked = o.get("locked").getAsBoolean();
        if (o.has("species")) species = o.get("species").getAsString();
    }

    // ── Factory: default presets ──────────────────────────────────────────────
    public static KartPreset rally() {
        KartPreset p = new KartPreset("rally", "Rally");
        p.slipperiness = 0.98f;
        p.stepHeight = 1.25f;
        p.gravity = -0.04;
        p.forwardAccel = 0.04f;
        p.backwardAccel = 0.005f;
        p.yawAccel = 1.0f;
        p.airControl = true;
        p.fallDamage = false;
        return p;
    }

    public static KartPreset drift() {
        KartPreset p = new KartPreset("drift", "Drift");
        p.slipperiness = 1.1f;
        p.stepHeight = 1.25f;
        p.gravity = -0.04;
        p.forwardAccel = 0.06f;
        p.backwardAccel = 0.005f;
        p.yawAccel = 2.0f;
        p.airControl = true;
        p.fallDamage = false;
        return p;
    }

    public static KartPreset parkour() {
        KartPreset p = new KartPreset("parkour", "Parkour");
        p.slipperiness = 0.98f;
        p.stepHeight = 2.0f;
        p.gravity = -0.03;
        p.forwardAccel = 0.04f;
        p.backwardAccel = 0.005f;
        p.yawAccel = 1.0f;
        p.jumpForce = 0.6f;
        p.airControl = true;
        p.airStepping = true;
        p.fallDamage = false;
        return p;
    }
}
