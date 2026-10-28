package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.LegendEntry;
import com.cobblecap.data.StatueStyle;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Spawns and manages "diorama" statues for CobbleCap Hall of Fame entries.
 *
 * Each spawned entity carries scoreboard tags:
 *   - cobblecap_statue                          (master tag)
 *   - cobblecap_statue_legend.&lt;playerLowercase&gt; (which legend)
 *   - cobblecap_statue_slot.&lt;player|0..5&gt;      (which slot)
 *   - cobblecap_statue_style.&lt;styleKey&gt;        (current style — drives tick effects)
 *
 * Style effects (glow + particles) are applied each tick via {@link #tick}.
 * Cleanup is by tag query — no persistent file needed.
 */
public class StatueManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    public static final String TAG_MASTER        = "cobblecap_statue";
    public static final String TAG_TEST          = "cobblecap_statue_test"; // marks entities spawned by /statue test
    public static final String TAG_CURRENT       = "cobblecap_statue_current"; // dynamic — refreshes when champion changes
    public static final String TAG_LEGEND_PREFIX = "cobblecap_statue_legend.";
    public static final String TAG_SLOT_PREFIX   = "cobblecap_statue_slot.";
    public static final String TAG_STYLE_PREFIX  = "cobblecap_statue_style.";

    private MinecraftServer server;
    /** Coalesce particle/glow refresh — once per 10 ticks. */
    private int tickCounter = 0;

    /** Tracks "current champion" diorama anchors so they can be respawned after a morph. */
    private final java.util.List<DioramaAnchor> currentChampionAnchors = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final java.nio.file.Path ANCHORS_PATH = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_dioramas.json");
    private static final com.google.gson.Gson ANCHORS_GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    /**
     * Mutable so {@link #restyleSelector} can update the styleKey when the user
     * runs /cobblecap statue style on a TAG_CURRENT diorama — keeps refresh()
     * consistent with the visible state.
     */
    public static class DioramaAnchor {
        public final String worldId;
        public final double x, y, z;
        public final float yaw;
        public volatile String styleKey;
        public volatile double scale;
        /** When true, refresh respawns ONLY the player statue (no Pokémon team).
         *  Set by /cobblecap statue place_current_player. Defaults false (full diorama). */
        public volatile boolean playerOnly;
        /** Snapshot of the LegendEntry used when this anchor was placed as a
         *  placeholder (no ranked champion existed yet). Persisted so the visible
         *  body survives restarts even before a real champion is crowned. Cleared
         *  once a real champion is identified — see refreshCurrentChampionDioramas. */
        public volatile com.cobblecap.data.LegendEntry placeholderLegend;
        public DioramaAnchor(String worldId, double x, double y, double z,
                              float yaw, String styleKey, double scale) {
            this(worldId, x, y, z, yaw, styleKey, scale, false, null);
        }
        public DioramaAnchor(String worldId, double x, double y, double z,
                              float yaw, String styleKey, double scale, boolean playerOnly) {
            this(worldId, x, y, z, yaw, styleKey, scale, playerOnly, null);
        }
        public DioramaAnchor(String worldId, double x, double y, double z,
                              float yaw, String styleKey, double scale, boolean playerOnly,
                              com.cobblecap.data.LegendEntry placeholderLegend) {
            this.worldId = worldId; this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.styleKey = styleKey; this.scale = scale;
            this.playerOnly = playerOnly;
            this.placeholderLegend = placeholderLegend;
        }
        public String worldId() { return worldId; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public float yaw() { return yaw; }
        public String styleKey() { return styleKey; }
        public double scale() { return scale; }
        public boolean playerOnly() { return playerOnly; }
        public com.cobblecap.data.LegendEntry placeholderLegend() { return placeholderLegend; }
    }

    private synchronized void loadAnchors() {
        try {
            if (!java.nio.file.Files.exists(ANCHORS_PATH)) return;
            try (java.io.Reader r = java.nio.file.Files.newBufferedReader(ANCHORS_PATH)) {
                com.google.gson.JsonObject root = ANCHORS_GSON.fromJson(r, com.google.gson.JsonObject.class);
                if (root == null || !root.has("anchors")) return;
                for (var el : root.getAsJsonArray("anchors")) {
                    com.google.gson.JsonObject o = el.getAsJsonObject();
                    com.cobblecap.data.LegendEntry placeholder = null;
                    if (o.has("placeholderLegend") && o.get("placeholderLegend").isJsonObject()) {
                        try { placeholder = com.cobblecap.data.LegendEntry
                                .fromJson(o.getAsJsonObject("placeholderLegend")); }
                        catch (Exception ignored) {}
                    }
                    currentChampionAnchors.add(new DioramaAnchor(
                            o.get("worldId").getAsString(),
                            o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                            o.get("yaw").getAsFloat(),
                            o.has("styleKey") ? o.get("styleKey").getAsString() : "natural",
                            o.has("scale") ? o.get("scale").getAsDouble() : 1.0,
                            o.has("playerOnly") && o.get("playerOnly").getAsBoolean(),
                            placeholder
                    ));
                }
                LOGGER.info("[CobbleCap] Loaded {} dynamic diorama anchor(s)", currentChampionAnchors.size());
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Failed to load diorama anchors: {}", e.getMessage());
        }
    }

    private synchronized void saveAnchors() {
        try {
            java.nio.file.Files.createDirectories(ANCHORS_PATH.getParent());
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (DioramaAnchor a : currentChampionAnchors) {
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("worldId", a.worldId());
                o.addProperty("x", a.x()); o.addProperty("y", a.y()); o.addProperty("z", a.z());
                o.addProperty("yaw", a.yaw());
                o.addProperty("styleKey", a.styleKey());
                o.addProperty("scale", a.scale());
                if (a.playerOnly()) o.addProperty("playerOnly", true);
                if (a.placeholderLegend() != null) o.add("placeholderLegend", a.placeholderLegend().toJson());
                arr.add(o);
            }
            root.add("anchors", arr);
            java.nio.file.Path tmp = ANCHORS_PATH.resolveSibling("cobblecap_dioramas.json.tmp");
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(tmp)) {
                ANCHORS_GSON.toJson(root, w);
            }
            try { java.nio.file.Files.move(tmp, ANCHORS_PATH,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception ignored) {
                java.nio.file.Files.move(tmp, ANCHORS_PATH,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Failed to save diorama anchors: {}", e.getMessage());
        }
    }

    /** ArmorStand UUID -> ChampionDoppelganger overlay providing the full-body player skin. */
    private final java.util.Map<UUID, ChampionDoppelganger> doppels = new java.util.concurrent.ConcurrentHashMap<>();

    public void init(MinecraftServer server) {
        this.server = server;
        ensureTeams(server);
        loadAnchors();
        // Sync doppels to viewers when they switch worlds / join.
        // The 3s auto-heal tick is the real safety net — JOIN refresh is just
        // an opportunistic fast-path so the player sees the statue immediately
        // if they spawn within range.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayerEntity p = handler.player;
            if (p == null) return;
            LOGGER.info("[CobbleCap][Doppel] JOIN event for {}", p.getName().getString());
            refreshDoppelsTo(p);
        });

        // Cleanup viewer state on disconnect so re-login re-triggers showTo.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (handler.player == null) return;
            UUID viewerUuid = handler.player.getUuid();
            for (ChampionDoppelganger d : doppels.values()) d.forgetViewer(viewerUuid);
        });

        // Re-attach a doppel when a tracker armor stand is re-loaded by the
        // chunk system (i.e. when a player gets close to a long-unloaded
        // diorama). Without this, the visible body would never reappear after
        // chunks drop out of memory.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, w) -> {
            try {
                if (!(entity instanceof ArmorStandEntity)) return;
                if (!entity.getCommandTags().contains(TAG_MASTER)) return;
                if (!entity.getCommandTags().contains(TAG_SLOT_PREFIX + "player")) return;
                if (doppels.containsKey(entity.getUuid())) return;
                String legendName = readLegendName(entity);
                if (legendName == null) return;
                LegendEntry legend = CobbleCapMod.INSTANCE.getLegendsStorage()
                        .findLatestForPlayerName(legendName, true);
                if (legend == null) return;
                spawnDoppelOverlay(w, entity.getUuid(), legend,
                        entity.getX(), entity.getY(), entity.getZ(), entity.getYaw());
            } catch (Exception ex) {
                LOGGER.debug("[CobbleCap] ENTITY_LOAD doppel re-attach failed: {}", ex.getMessage());
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, w) -> {
            if (entity instanceof ArmorStandEntity) clearDoppel(entity.getUuid());
        });

        // CRITICAL: per-player tracking-range visibility. The chunk-load event
        // above is not enough — Minecraft drops the visible fake-player from
        // the client when the player walks out of *entity tracking* range (smaller
        // than chunk range). When the player comes back, the server doesn't
        // re-send our doppel spawn packets. Hook entity tracking start/stop on
        // the tracker armor stand to keep the doppel in sync with the real
        // entity's visibility.
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (!(entity instanceof ArmorStandEntity)) return;
            if (!entity.getCommandTags().contains(TAG_MASTER)) return;
            if (!entity.getCommandTags().contains(TAG_SLOT_PREFIX + "player")) return;
            ChampionDoppelganger d = doppels.get(entity.getUuid());
            if (d == null) {
                // Doppel not in memory yet (e.g., fresh chunk load happened after
                // server boot). Try to materialize it.
                String legendName = readLegendName(entity);
                if (legendName == null) return;
                LegendEntry legend = CobbleCapMod.INSTANCE.getLegendsStorage()
                        .findLatestForPlayerName(legendName, true);
                if (legend == null) return;
                if (!spawnDoppelOverlay((ServerWorld) entity.getEntityWorld(),
                        entity.getUuid(), legend,
                        entity.getX(), entity.getY(), entity.getZ(), entity.getYaw())) return;
                d = doppels.get(entity.getUuid());
            }
            if (d != null) d.showTo(viewer);
        });
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.STOP_TRACKING.register((entity, viewer) -> {
            if (!(entity instanceof ArmorStandEntity)) return;
            if (!entity.getCommandTags().contains(TAG_MASTER)) return;
            ChampionDoppelganger d = doppels.get(entity.getUuid());
            if (d != null) d.hideFrom(viewer);
        });

        // Re-attach a doppelganger overlay to every persistent player-slot tracker
        // armor stand found in any world. Without this, armor stands persist
        // across restarts but their visual overlays would be missing.
        int rebound = rebindDoppelsOnBoot(server);
        LOGGER.info("[CobbleCap] StatueManager initialized ({} current-champion anchor(s), {} doppel(s) reattached)",
                currentChampionAnchors.size(), rebound);

        try { refreshCurrentChampionDioramas(server); }
        catch (Exception e) { LOGGER.debug("[CobbleCap] post-init refresh skipped: {}", e.getMessage()); }
    }

    /** Rebuild the {@code doppels} map by scanning every world for player-slot trackers. */
    private int rebindDoppelsOnBoot(MinecraftServer server) {
        int attached = 0;
        String playerSlot = TAG_SLOT_PREFIX + "player";
        for (ServerWorld w : server.getWorlds()) {
            for (Entity e : w.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_MASTER)) continue;
                if (!e.getCommandTags().contains(playerSlot)) continue;
                String legendName = readLegendName(e);
                if (legendName == null) continue;
                LegendEntry legend = CobbleCapMod.INSTANCE.getLegendsStorage()
                        .findLatestForPlayerName(legendName, true);
                if (legend == null) continue;
                if (spawnDoppelOverlay(w, e.getUuid(), legend,
                        e.getX(), e.getY(), e.getZ(), e.getYaw())) {
                    attached++;
                }
            }
        }
        return attached;
    }

    /** Re-broadcast every doppel to a freshly-joined viewer. */
    public void refreshDoppelsTo(ServerPlayerEntity viewer) {
        int total = doppels.size();
        int considered = 0;
        for (ChampionDoppelganger d : doppels.values()) {
            // Compare RegistryKeys (identifier strings) rather than instance refs;
            // ServerWorld instance equality has bitten us before during world reloads.
            if (d.getWorld().getRegistryKey().equals(viewer.getServerWorld().getRegistryKey())) {
                d.showTo(viewer);
                considered++;
            }
        }
        LOGGER.info("[CobbleCap][Doppel] refreshDoppelsTo viewer={} dim={} doppels(total/same-dim)={}/{}",
                viewer.getName().getString(),
                viewer.getServerWorld().getRegistryKey().getValue(),
                total, considered);
    }

    /** Ensure one scoreboard team per style exists (drives glow outline color). */
    private static void ensureTeams(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        for (StatueStyle s : StatueStyle.values()) {
            String teamName = teamName(s);
            Team t = sb.getTeam(teamName);
            if (t == null) t = sb.addTeam(teamName);
            // Map style RGB → Formatting (vanilla glow uses Formatting color, not raw RGB)
            t.setColor(closestFormatting(s.glowColor()));
            t.setFriendlyFireAllowed(true);
            t.setShowFriendlyInvisibles(false);
        }
    }

    private static String teamName(StatueStyle s) { return "cgcap_" + s.key(); }

    private static Formatting closestFormatting(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        // Pick the named MC color closest in RGB space
        Formatting best = Formatting.WHITE; int bestDist = Integer.MAX_VALUE;
        for (Formatting f : Formatting.values()) {
            if (!f.isColor() || f.getColorValue() == null) continue;
            int v = f.getColorValue();
            int dr = ((v >> 16) & 0xFF) - r, dg = ((v >> 8) & 0xFF) - g, db = (v & 0xFF) - b;
            int d = dr*dr + dg*dg + db*db;
            if (d < bestDist) { bestDist = d; best = f; }
        }
        return best;
    }

    /**
     * Per-tick refresh of style effects. Cheap: only iterates entities carrying
     * the master tag; skipped 9 out of 10 ticks.
     */
    public void tick() {
        if (server == null) return;
        if (++tickCounter % 10 != 0) return;

        // Auto-heal doppel visibility every 60 ticks (3s). Catches every case
        // where JOIN/START_TRACKING didn't deliver — e.g., the player joined
        // before doppels existed, the world ref equality drifted across a
        // reload, or marker armor stands aren't propagating START_TRACKING
        // properly. Range 128 covers a comfortable margin over vanilla's
        // 10-chunk entity tracking radius (160 blocks).
        if (tickCounter % 60 == 0 && !doppels.isEmpty()) {
            int totalShown = 0;
            for (ChampionDoppelganger d : doppels.values()) {
                totalShown += d.ensureVisibleToNearby(128.0);
            }
            if (totalShown > 0) {
                LOGGER.info("[CobbleCap][Doppel] auto-heal: shown to {} new viewer(s) across {} doppel(s)",
                        totalShown, doppels.size());
            }
        }

        Scoreboard sb = server.getScoreboard();

        for (ServerWorld world : server.getWorlds()) {
            for (Entity e : world.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_MASTER)) continue;
                StatueStyle style = readStyle(e);

                // Keep entity glowing in the team color (refresh GLOWING status periodically)
                if (style.glow() && e instanceof LivingEntity le) {
                    le.addStatusEffect(new StatusEffectInstance(
                            StatusEffects.GLOWING, 30, 0, false, false, false));
                }

                // Particle aura — small puff above the entity
                ParticleEffect particle = style.particle();
                if (particle != null) {
                    double hx = e.getX();
                    double hy = e.getY() + (e.getHeight() * 0.6);
                    double hz = e.getZ();
                    world.spawnParticles(particle, hx, hy, hz,
                            3,
                            e.getWidth() * 0.4,
                            e.getHeight() * 0.3,
                            e.getWidth() * 0.4,
                            style.particleSpeed());
                }

                // Ensure the entity is on the right scoreboard team (glow color)
                String wantTeam = teamName(style);
                String currentTeamName = e.getScoreboardTeam() == null ? "" : e.getScoreboardTeam().getName();
                if (!wantTeam.equals(currentTeamName)) {
                    Team t = sb.getTeam(wantTeam);
                    if (t != null) sb.addScoreHolderToTeam(scoreHolderKey(e), t);
                }
            }
        }
    }

    private static String scoreHolderKey(Entity e) {
        // Entities are referenced by their UUID string (not name) in vanilla scoreboards.
        return e.getUuidAsString();
    }

    // ── Public API: spawn ────────────────────────────────────────────────────

    public record PlaceResult(boolean ok, String message, int entitiesSpawned) {}

    /** Spawn the full diorama: player NPC + every Pokémon in the legend's team. */
    public PlaceResult placeFullDiorama(ServerPlayerEntity caster, LegendEntry legend,
                                          StatueStyle style, double scale) {
        if (caster == null || legend == null) return new PlaceResult(false, "missing args", 0);
        // Remove any previous diorama for the same legend before re-placing
        removeAllForLegend(caster.getServer(), legend.playerName());

        ServerWorld world = caster.getServerWorld();
        Vec3d origin = caster.getPos();
        float casterYaw = caster.getYaw();
        Vec3d playerPos = origin;
        int spawned = 0;
        java.util.List<String> failedSpecies = new java.util.ArrayList<>();

        if (spawnPlayerStatue(world, legend, playerPos, casterYaw, style, scale)) spawned++;

        // Pokémon arranged in a row 3 blocks behind the player statue
        Vec3d behind = forwardVec(casterYaw, -3.5);
        Vec3d rowCenter = playerPos.add(behind);
        Vec3d right = forwardVec(casterYaw + 90f, 1.0);

        int team = Math.min(6, legend.team().size());
        for (int i = 0; i < team; i++) {
            double offset = (i - (team - 1) / 2.0) * 2.0;
            Vec3d pos = rowCenter.add(right.multiply(offset));
            if (spawnPokemonStatue(world, legend, i, pos, casterYaw, style, scale, caster)) spawned++;
            else failedSpecies.add(legend.team().get(i).species());
        }

        String msg = "spawned " + spawned + " entit(y/ies)";
        if (!failedSpecies.isEmpty()) {
            msg += " — failed: " + String.join(", ", failedSpecies);
        }
        return new PlaceResult(true, msg, spawned);
    }

    /** Spawn only the player statue at caster's position. Replaces existing player slot. */
    public PlaceResult placePlayerOnly(ServerPlayerEntity caster, LegendEntry legend,
                                        StatueStyle style, double scale) {
        if (caster == null || legend == null) return new PlaceResult(false, "missing args", 0);
        removeSlot(caster.getServer(), legend.playerName(), "player");
        boolean ok = spawnPlayerStatue(caster.getServerWorld(), legend,
                caster.getPos(), caster.getYaw(), style, scale);
        return new PlaceResult(ok, ok ? "player statue placed" : "spawn failed", ok ? 1 : 0);
    }

    /** Spawn one specific Pokémon (0-indexed) from the legend's team at caster's position. */
    public PlaceResult placePokemonOnly(ServerPlayerEntity caster, LegendEntry legend, int slot,
                                          StatueStyle style, double scale) {
        if (caster == null || legend == null) return new PlaceResult(false, "missing args", 0);
        if (slot < 0 || slot >= legend.team().size())
            return new PlaceResult(false, "slot out of range", 0);
        removeSlot(caster.getServer(), legend.playerName(), Integer.toString(slot));
        boolean ok = spawnPokemonStatue(caster.getServerWorld(), legend, slot,
                caster.getPos(), caster.getYaw(), style, scale, caster);
        return new PlaceResult(ok, ok ? "pokemon statue placed" : "spawn failed", ok ? 1 : 0);
    }

    // ── Public API: manipulate ───────────────────────────────────────────────

    /** Remove every statue entity tagged with the given legend's player name. */
    public int removeAllForLegend(MinecraftServer server, String playerName) {
        return forEachMatching(server, playerName, null, e -> despawnWithDoppel(e));
    }

    /**
     * Place a diorama for the *current* champion (live binding). After every
     * RctTrainerMorpher flush, dioramas tagged TAG_CURRENT are wiped and respawned
     * with the new champion's data — see {@link #refreshCurrentChampionDioramas}.
     */
    public PlaceResult placeCurrentChampionDiorama(ServerPlayerEntity caster, StatueStyle style, double scale) {
        return placeCurrentChampionDiorama(caster, style, scale, /*playerOnly=*/false);
    }

    /** Convenience: place the dynamic champion diorama with only the player statue,
     *  no Pokémon team. Refresh on champion change re-spawns only the player too. */
    public PlaceResult placeCurrentChampionPlayerOnly(ServerPlayerEntity caster, StatueStyle style, double scale) {
        return placeCurrentChampionDiorama(caster, style, scale, /*playerOnly=*/true);
    }

    /** Internal: place the dynamic champion diorama. When {@code playerOnly} is
     *  true, the Pokémon row is skipped — both on initial placement AND on every
     *  subsequent refresh triggered by a champion change. */
    private PlaceResult placeCurrentChampionDiorama(ServerPlayerEntity caster, StatueStyle style,
                                                      double scale, boolean playerOnly) {
        if (caster == null) return new PlaceResult(false, "missing caster", 0);
        com.cobblecap.data.LegendEntry champion = currentChampionEntry();
        boolean placeholder = false;
        com.cobblecap.data.LegendEntry placeholderLegend = null;
        if (champion == null) {
            // No real champion yet — use the caster as a placeholder. The
            // anchor IS saved (with the placeholder legend serialized), so
            // both: (a) the visible body survives restarts before a real
            // champion is crowned, AND (b) refreshCurrentChampionDioramas()
            // automatically replaces this placeholder with the new champion's
            // profile + team once one exists.
            java.util.List<LegendEntry.TeamMember> team =
                    ChampionManager.snapshotPlayerTeam(caster);
            champion = new LegendEntry(
                    caster.getUuid(), caster.getName().getString(),
                    System.currentTimeMillis(),
                    /*firstEver=*/false,
                    /*isTest=*/false,
                    /*bestTimeMs=*/0L,
                    team);
            placeholder = true;
            placeholderLegend = champion;
        }

        // Record anchor so we can refresh later
        ServerWorld world = caster.getServerWorld();
        DioramaAnchor anchor = new DioramaAnchor(
                world.getRegistryKey().getValue().toString(),
                caster.getX(), caster.getY(), caster.getZ(),
                caster.getYaw(), style.key(), scale, playerOnly, placeholderLegend);
        currentChampionAnchors.add(anchor);
        saveAnchors();

        PlaceResult res = playerOnly
                ? placePlayerOnly(caster, champion, style, scale)
                : placeFullDiorama(caster, champion, style, scale);
        // Tag all entities for this legend as TAG_CURRENT so refresh can find them
        forEachMatching(caster.getServer(), champion.playerName(), null, e -> {
            if (!e.getCommandTags().contains(TAG_CURRENT)) e.addCommandTag(TAG_CURRENT);
            return true;
        });
        if (placeholder) {
            return new PlaceResult(res.ok(),
                    "PLACEHOLDER (no champion yet — using your profile; auto-replaces when first champion is crowned) — " + res.message(),
                    res.entitiesSpawned());
        }
        return res;
    }

    /**
     * Wipe + respawn every "current champion" diorama using whoever is the
     * current champion now. Called after RctTrainerMorpher applies a morph.
     */
    public void refreshCurrentChampionDioramas(MinecraftServer server) {
        if (server == null || currentChampionAnchors.isEmpty()) return;
        com.cobblecap.data.LegendEntry champion = currentChampionEntry();
        boolean usingRealChampion = champion != null;

        // Remove existing current-tagged entities (and their overlay doppels).
        // We wipe + respawn even in placeholder mode so doppels are reattached
        // after a server restart (the rebind-on-boot pass can't resolve a
        // placeholder caster who has no LegendsStorage entry).
        for (ServerWorld w : server.getWorlds()) {
            List<Entity> matches = new ArrayList<>();
            for (Entity e : w.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_MASTER)) continue;
                if (!e.getCommandTags().contains(TAG_CURRENT)) continue;
                matches.add(e);
            }
            for (Entity e : matches) despawnWithDoppel(e);
        }

        // Respawn at each anchor. Use the real champion when available;
        // otherwise fall back to each anchor's persisted placeholder so the
        // visible body is always present.
        int placeholderCount = 0;
        for (DioramaAnchor a : currentChampionAnchors) {
            ServerWorld w = findWorldById(server, a.worldId());
            if (w == null) continue;
            com.cobblecap.data.LegendEntry target = usingRealChampion ? champion : a.placeholderLegend();
            if (target == null) continue; // no champion AND no placeholder — leave empty
            if (!usingRealChampion) placeholderCount++;
            spawnDioramaAtAnchor(w, target, a);
            // Once a real champion is in play, drop the anchor's placeholder so
            // future refreshes don't waste storage on stale snapshots.
            if (usingRealChampion && a.placeholderLegend != null) a.placeholderLegend = null;
        }
        if (usingRealChampion) saveAnchors();
        LOGGER.info("[CobbleCap] Refreshed {} current-champion diorama(s) -> {} ({})",
                currentChampionAnchors.size(),
                usingRealChampion ? champion.playerName() : "placeholders",
                usingRealChampion ? "real" : (placeholderCount + " placeholder(s)"));
    }

    private static ServerWorld findWorldById(MinecraftServer server, String id) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(id)) return w;
        }
        return null;
    }

    /** Spawn a full diorama directly at an anchor (used by refresh).
     *  Picks any online op to satisfy Cobblemon spawn command source requirement. */
    private void spawnDioramaAtAnchor(ServerWorld world,
                                        com.cobblecap.data.LegendEntry legend,
                                        DioramaAnchor a) {
        StatueStyle style = StatueStyle.fromKey(a.styleKey());
        Vec3d origin = new Vec3d(a.x(), a.y(), a.z());
        float yaw = a.yaw();

        if (spawnPlayerStatue(world, legend, origin, yaw, style, a.scale())) {
            forEachLastSpawnedAddTag(world, legend.playerName(), TAG_CURRENT);
        }
        if (a.playerOnly()) return; // player-only anchor: skip Pokémon row.

        ServerPlayerEntity proxyCaster = findOnlineOp(world);
        Vec3d behind = forwardVec(yaw, -3.5);
        Vec3d rowCenter = origin.add(behind);
        Vec3d right = forwardVec(yaw + 90f, 1.0);
        int team = Math.min(6, legend.team().size());
        for (int i = 0; i < team; i++) {
            double offset = (i - (team - 1) / 2.0) * 2.0;
            Vec3d pos = rowCenter.add(right.multiply(offset));
            if (spawnPokemonStatue(world, legend, i, pos, yaw, style, a.scale(), proxyCaster)) {
                forEachLastSpawnedAddTag(world, legend.playerName(), TAG_CURRENT);
            }
        }
    }

    /** Find any online op player in the given world (or anywhere) — used as a
     *  command-source proxy for Cobblemon's /spawnpokemonat which requires
     *  source.getPlayer() != null. Returns null if no op is online. */
    private ServerPlayerEntity findOnlineOp(ServerWorld world) {
        if (server == null) return null;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.hasPermissionLevel(2)) return p;
        }
        return null;
    }

    private static void forEachLastSpawnedAddTag(ServerWorld world, String playerName, String tag) {
        String legendTag = TAG_LEGEND_PREFIX + playerName.toLowerCase(Locale.ROOT);
        for (Entity e : world.iterateEntities()) {
            if (!e.getCommandTags().contains(TAG_MASTER)) continue;
            if (!e.getCommandTags().contains(legendTag)) continue;
            if (!e.getCommandTags().contains(tag)) e.addCommandTag(tag);
        }
    }

    /**
     * Resolve the current Maître de la Ligue <strong>Compétitif</strong> — the
     * single player whose tenure is open in {@link com.cobblecap.data.TenureStorage}.
     * The classic mode is intentionally ignored: classic winners earn a Hall of
     * Fame entry and a chrono record, but no statue/diorama. Only the ranked
     * champion in titre gets the dynamic diorama.
     * <p>
     * Returns {@code null} when no ranked champion has been crowned yet, in
     * which case {@code placeCurrentChampionDiorama} falls back to a placeholder
     * using the command caster's profile.
     */
    private static com.cobblecap.data.LegendEntry currentChampionEntry() {
        var tenure = CobbleCapMod.INSTANCE.getTenureStorage().current();
        if (tenure == null) return null; // no ranked champion yet
        var legends = CobbleCapMod.INSTANCE.getLegendsStorage().getAll();
        // Resolve via fallback ladder so we ALWAYS reflect the actual current
        // tenure even when LegendsStorage data is inconsistent (e.g. legend
        // saved with mode=classic before the ranked promotion path persisted
        // a separate ranked entry):
        //   1. Best: RANKED non-test entry matching championUuid
        //   2. OK:   ANY non-test entry matching championUuid (uses team data)
        //   3. Min:  synthesize from Tenure alone — no team but correct
        //            UUID/name so the doppel shows the right player skin.
        com.cobblecap.data.LegendEntry rankedMatch = null;
        com.cobblecap.data.LegendEntry anyMatch = null;
        for (var e : legends) {
            if (e.isTest()) continue;
            if (!e.uuid().equals(tenure.championUuid)) continue;
            if (e.mode() == com.cobblecap.data.LegendEntry.Mode.RANKED) {
                if (rankedMatch == null || e.completedAt() > rankedMatch.completedAt()) rankedMatch = e;
            }
            if (anyMatch == null || e.completedAt() > anyMatch.completedAt()) anyMatch = e;
        }
        if (rankedMatch != null) return rankedMatch;
        if (anyMatch != null) return anyMatch;
        // Synthesize a minimal LegendEntry from the Tenure so the diorama still
        // shows the correct champion's skin + name when no full legend exists.
        return new com.cobblecap.data.LegendEntry(
                tenure.championUuid, tenure.championName, tenure.startedAt,
                /*firstEver=*/false, /*isTest=*/false, tenure.bestTimeMs,
                new java.util.ArrayList<>(), com.cobblecap.data.LegendEntry.Mode.RANKED);
    }

    public int clearCurrentChampionAnchors() {
        int n = currentChampionAnchors.size();
        currentChampionAnchors.clear();
        saveAnchors();
        return n;
    }

    /**
     * Aggressive cleanup — removes every entity carrying ANY cobblecap statue
     * tag across all loaded worlds, despawns every doppelganger overlay, and
     * clears the anchor list. Catches "invisible" statues (armor stands whose
     * doppel never re-attached after a restart or a botched placement) since
     * the scan is tag-based rather than legend-name-based.
     * <p>
     * Returns the total number of entities despawned.
     */
    public int wipeAllStatues(MinecraftServer server) {
        if (server == null) return 0;
        int removed = 0;
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> matches = new ArrayList<>();
            for (Entity e : world.iterateEntities()) {
                if (hasAnyStatueTag(e)) matches.add(e);
            }
            for (Entity e : matches) { despawnWithDoppel(e); removed++; }
        }
        // Sweep any lingering doppels whose tracker armor stand was already gone
        // (defensive — despawnWithDoppel above handles the common case, but
        // doppels could survive if their tracker was removed without our help).
        for (UUID id : new java.util.ArrayList<>(doppels.keySet())) clearDoppel(id);
        // Clear anchors so the next refresh starts from a blank slate.
        int anchorsCleared = currentChampionAnchors.size();
        currentChampionAnchors.clear();
        saveAnchors();
        LOGGER.info("[CobbleCap] wipeAllStatues: removed {} entit(y/ies), cleared {} anchor(s), reset doppel map",
                removed, anchorsCleared);
        return removed;
    }

    /** Does this entity carry ANY tag in the cobblecap statue namespace? */
    private static boolean hasAnyStatueTag(Entity e) {
        for (String tag : e.getCommandTags()) {
            if (tag.equals(TAG_MASTER) || tag.equals(TAG_TEST) || tag.equals(TAG_CURRENT)
                    || tag.startsWith(TAG_LEGEND_PREFIX) || tag.startsWith(TAG_SLOT_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove ONLY entities flagged as "test" for the given legend. Safer than
     * removeAllForLegend when a player has both a real Master diorama and a test one.
     */
    public int removeTestsForLegend(MinecraftServer server, String playerName) {
        if (server == null) return 0;
        String legendTag = TAG_LEGEND_PREFIX + playerName.toLowerCase(Locale.ROOT);
        int count = 0;
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> matches = new ArrayList<>();
            for (Entity e : world.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_MASTER)) continue;
                if (!e.getCommandTags().contains(TAG_TEST)) continue;
                if (!e.getCommandTags().contains(legendTag)) continue;
                matches.add(e);
            }
            // Use despawnWithDoppel so the packet-only doppelganger overlay is
            // hidden from viewers (otherwise the visible player skin persists
            // until the viewer relogs).
            for (Entity e : matches) { despawnWithDoppel(e); count++; }
        }
        return count;
    }

    /** Remove all entities for one slot of a legend (e.g. just slot "3" or "player"). */
    public int removeSlot(MinecraftServer server, String playerName, String slot) {
        return forEachMatching(server, playerName, slot, e -> despawnWithDoppel(e));
    }

    public int moveSelector(MinecraftServer server, String playerName, String slot,
                              double dx, double dy, double dz) {
        return forEachMatching(server, playerName, slot, e -> {
            e.move(MovementType.SELF, new Vec3d(dx, dy, dz));
            e.refreshPositionAndAngles(e.getX() + dx, e.getY() + dy, e.getZ() + dz, e.getYaw(), e.getPitch());
            respawnDoppelFromEntity(e);
            return true;
        });
    }

    public int rotateSelector(MinecraftServer server, String playerName, String slot, float yaw) {
        return forEachMatching(server, playerName, slot, e -> {
            e.setYaw(yaw);
            e.setHeadYaw(yaw);
            e.setBodyYaw(yaw);
            respawnDoppelFromEntity(e);
            return true;
        });
    }

    public int scaleSelector(MinecraftServer server, String playerName, String slot, double factor) {
        return forEachMatching(server, playerName, slot, e -> applyScale(e, factor));
    }

    public int restyleSelector(MinecraftServer server, String playerName, String slot, StatueStyle style) {
        // Track if any TAG_CURRENT entity was restyled — if so, update the matching
        // anchor's styleKey so a subsequent refresh respawns with the new style.
        final boolean[] touchedCurrent = {false};
        int n = forEachMatching(server, playerName, slot, e -> {
            Set<String> tags = new HashSet<>(e.getCommandTags());
            for (String t : tags) {
                if (t.startsWith(TAG_STYLE_PREFIX)) e.removeCommandTag(t);
            }
            e.addCommandTag(TAG_STYLE_PREFIX + style.key());
            if (tags.contains(TAG_CURRENT)) touchedCurrent[0] = true;
            // Ensure ArmorStand stays invisible + bare (player statues are skin-only).
            if (e instanceof ArmorStandEntity stand) {
                stand.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
                stand.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
                stand.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
                stand.setInvisible(true);
                // Propagate glow to the doppelganger overlay (the visible body).
                // The stand itself is invisible/marker → its own glow is never
                // rendered. Drive the visual change via the fake player's
                // tracker + a scoreboard team keyed on the synthetic UUID.
                ChampionDoppelganger d = doppels.get(e.getUuid());
                if (d == null) {
                    // Doppel missing (chunk unload races, etc.) — re-spawn it
                    respawnDoppelFromEntity(e);
                    d = doppels.get(e.getUuid());
                }
                if (d != null) {
                    String wantTeam = teamName(style);
                    Team t = server.getScoreboard().getTeam(wantTeam);
                    if (t != null) {
                        // Vanilla glow color for *player entities* is resolved
                        // by NAME lookup (not UUID) — add the doppel display name
                        // (= legend.playerName) so the outline picks up the team color.
                        // Also add UUID for entity-style lookups as a defensive belt.
                        String legendName = readLegendName(e);
                        if (legendName != null) {
                            server.getScoreboard().addScoreHolderToTeam(legendName, t);
                        }
                        server.getScoreboard().addScoreHolderToTeam(
                                d.syntheticUuid().toString(), t);
                    }
                    d.setGlowingAndRebroadcast(style.glow());
                }
            }
            return true;
        });
        if (touchedCurrent[0]) {
            // Find anchors whose player statue is at a position matching this player's diorama
            // (simplest heuristic: update ALL anchors of the player's current diorama).
            // We don't track per-anchor player names, so re-style every anchor whose nearest
            // master entity belongs to this player. Cheap implementation: update all anchors.
            String legendTag = TAG_LEGEND_PREFIX + playerName.toLowerCase(Locale.ROOT);
            for (DioramaAnchor a : currentChampionAnchors) {
                ServerWorld w = findWorldById(server, a.worldId());
                if (w == null) continue;
                // Sample a player-slot entity nearby to confirm it belongs to this player
                boolean matches = false;
                for (Entity e : w.iterateEntities()) {
                    if (!e.getCommandTags().contains(TAG_MASTER)) continue;
                    if (!e.getCommandTags().contains(legendTag)) continue;
                    double dx = e.getX() - a.x(), dy = e.getY() - a.y(), dz = e.getZ() - a.z();
                    if (dx*dx + dy*dy + dz*dz < 36) { matches = true; break; }
                }
                if (matches) a.styleKey = style.key();
            }
            saveAnchors();
        }
        return n;
    }

    /** Iterate matching entities and apply an action. Returns the count handled. */
    private int forEachMatching(MinecraftServer server, String playerName, String slotOrNull,
                                  java.util.function.Function<Entity, Boolean> fn) {
        if (server == null) return 0;
        String legendTag = TAG_LEGEND_PREFIX + playerName.toLowerCase(Locale.ROOT);
        int count = 0;
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> matches = new ArrayList<>();
            for (Entity e : world.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_MASTER)) continue;
                if (!e.getCommandTags().contains(legendTag)) continue;
                if (slotOrNull != null) {
                    String slotTag = TAG_SLOT_PREFIX + slotOrNull;
                    if (!e.getCommandTags().contains(slotTag)) continue;
                }
                matches.add(e);
            }
            for (Entity e : matches) {
                try {
                    if (Boolean.TRUE.equals(fn.apply(e))) count++;
                } catch (Exception ex) {
                    LOGGER.warn("[CobbleCap] statue action failed: {}", ex.getMessage());
                }
            }
        }
        return count;
    }

    /** Return one descriptor per placed diorama (grouped by playerName). */
    public List<DioramaInfo> list(MinecraftServer server) {
        if (server == null) return List.of();
        java.util.Map<String, DioramaInfo> map = new java.util.LinkedHashMap<>();
        for (ServerWorld world : server.getWorlds()) {
            for (Entity e : world.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_MASTER)) continue;
                String legendName = readLegendName(e);
                if (legendName == null) continue;
                DioramaInfo info = map.computeIfAbsent(legendName,
                        k -> new DioramaInfo(k, world.getRegistryKey().getValue().toString(),
                                e.getX(), e.getY(), e.getZ(), 0));
                info.count++;
            }
        }
        return new ArrayList<>(map.values());
    }

    public static class DioramaInfo {
        public final String playerName;
        public final String world;
        public final double x, y, z;
        public int count;
        DioramaInfo(String playerName, String world, double x, double y, double z, int count) {
            this.playerName = playerName; this.world = world;
            this.x = x; this.y = y; this.z = z; this.count = count;
        }
    }

    // ── Internal: spawning ───────────────────────────────────────────────────

    /**
     * Spawn an INVISIBLE marker armor stand as the persistence/tracker anchor,
     * then spawn a full-body fake-player doppelganger overlay (with real UUID
     * + Mojang-signed textures for offline champions) so the visible statue is
     * the actual champion's player skin.
     *
     * Why not just equip a player head on a visible armor stand: the user
     * wants the FULL player body (arms, legs, model proportions), not an
     * armor stand body with a head bolted on top.
     */
    private boolean spawnPlayerStatue(ServerWorld world, LegendEntry legend, Vec3d pos,
                                        float yaw, StatueStyle style, double scale) {
        try {
            ArmorStandEntity stand = new ArmorStandEntity(world, pos.x, pos.y, pos.z);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            stand.setShowArms(true);
            stand.setHideBasePlate(true);
            stand.setSilent(true);
            stand.setYaw(yaw);
            stand.setHeadYaw(yaw);
            stand.setBodyYaw(yaw);

            // Custom name not shown above stand — the doppel carries the
            // player name itself (via its GameProfile display name).
            stand.setCustomName(Text.literal(legend.playerName()).formatted(Formatting.GOLD));
            stand.setCustomNameVisible(false);

            // INVISIBLE armor stand: it's a tracker for commands (move/scale)
            // and for entity-load events that re-attach the doppel; the
            // visible part is the doppel overlay spawned just below.
            stand.setInvisible(true);
            try {
                NbtCompound nbt = new NbtCompound();
                stand.writeNbt(nbt);
                nbt.putBoolean("Marker", true);
                stand.readNbt(nbt);
            } catch (Exception ignored) {}

            // Tags
            stand.addCommandTag(TAG_MASTER);
            if (legend.isTest()) stand.addCommandTag(TAG_TEST);
            stand.addCommandTag(TAG_LEGEND_PREFIX + legend.playerName().toLowerCase(Locale.ROOT));
            stand.addCommandTag(TAG_SLOT_PREFIX + "player");
            stand.addCommandTag(TAG_STYLE_PREFIX + style.key());

            applyScale(stand, scale);

            world.spawnEntity(stand);

            // Spawn the full-body player-skin doppelganger overlay (uses real
            // UUID + Mojang-signed textures for offline champions).
            spawnDoppelOverlay(world, stand.getUuid(), legend, pos.x, pos.y, pos.z, yaw);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] spawnPlayerStatue failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Spawn (or replace) the player-skin doppelganger overlay tied to a tracker
     * armor stand. Looks up the source player's GameProfile via the user cache.
     * Returns true if a doppel was spawned, false if no profile was available.
     */
    private boolean spawnDoppelOverlay(ServerWorld world, UUID anchorUuid, LegendEntry legend,
                                         double x, double y, double z, float yaw) {
        if (server == null) return false;
        try {
            // Full 4-tier resolution: online ▸ userCache ▸ session cache ▸ Mojang HTTP.
            com.mojang.authlib.GameProfile profile = MojangProfileFetcher.resolve(
                    server, legend.uuid(), legend.playerName());
            if (profile == null) {
                LOGGER.warn("[CobbleCap] No profile with textures for {} ({}) — doppel will not render",
                        legend.playerName(), legend.uuid());
                return false;
            }
            boolean hasTex = profile.getProperties().containsKey("textures");
            int propCount = profile.getProperties().size();
            LOGGER.info("[CobbleCap][Doppel] spawn for legend={} uuid={} profile-props={} hasTextures={}",
                    legend.playerName(), legend.uuid(), propCount, hasTex);

            // Replace any existing doppel for this anchor
            ChampionDoppelganger existing = doppels.remove(anchorUuid);
            if (existing != null) existing.hideFromAll();

            ChampionDoppelganger d = new ChampionDoppelganger(server, world, profile, x, y, z, yaw, 0f);
            LOGGER.info("[CobbleCap][Doppel] built: syntheticUuid={} (real={})",
                    d.syntheticUuid(), d.syntheticUuid().equals(legend.uuid()));

            // CRITICAL DEDUP: two ServerPlayerEntity instances sharing the same
            // UUID confuse the vanilla skin texture cache on the client → Steve
            // fallback. This can happen when ENTITY_LOAD listener fires for a
            // freshly-spawned stand AND spawnPlayerStatue calls us explicitly,
            // or when an old anchor lingers in the map after a wipe-and-replace.
            // Kill any other doppel sharing this synthetic UUID before put().
            UUID fakeUuid = d.syntheticUuid();
            for (java.util.Map.Entry<UUID, ChampionDoppelganger> en :
                    new java.util.ArrayList<>(doppels.entrySet())) {
                if (en.getKey().equals(anchorUuid)) continue;
                if (en.getValue().syntheticUuid().equals(fakeUuid)) {
                    LOGGER.warn("[CobbleCap][Doppel] DEDUP: removing stale doppel for syntheticUuid={} (old anchor={}, new anchor={})",
                            fakeUuid, en.getKey(), anchorUuid);
                    doppels.remove(en.getKey());
                    en.getValue().hideFromAll();
                }
            }

            // Look up the anchor entity to read its current StatueStyle + scale
            Entity anchor = world.getEntity(anchorUuid);
            StatueStyle st = anchor != null ? readStyle(anchor) : StatueStyle.NATURAL;
            d.setStyle(st);
            double anchorScale = anchor instanceof LivingEntity le ? readScaleAttribute(le) : 1.0;
            if (anchorScale != 1.0) d.setScale(anchorScale);
            doppels.put(anchorUuid, d);
            int viewers = d.showToWorld();
            LOGGER.info("[CobbleCap][Doppel] showToWorld -> {} viewer(s)", viewers);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] spawnDoppelOverlay failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Hide and forget the doppel attached to this anchor UUID, if any. */
    private void clearDoppel(UUID anchorUuid) {
        ChampionDoppelganger d = doppels.remove(anchorUuid);
        if (d != null) d.hideFromAll();
    }

    /** Discard a tracker entity and clean up its overlay doppel if any. */
    private boolean despawnWithDoppel(Entity e) {
        clearDoppel(e.getUuid());
        e.discard();
        return true;
    }

    /**
     * After a tracker armor stand is moved or rotated, re-spawn its doppel
     * overlay at the new pose.
     */
    private void respawnDoppelFromEntity(Entity e) {
        if (server == null) return;
        if (!(e.getEntityWorld() instanceof ServerWorld w)) return;
        if (!e.getCommandTags().contains(TAG_SLOT_PREFIX + "player")) return;
        String legendName = readLegendName(e);
        if (legendName == null) return;
        LegendEntry legend = CobbleCapMod.INSTANCE.getLegendsStorage().findLatestForPlayerName(legendName, true);
        if (legend == null) return;
        spawnDoppelOverlay(w, e.getUuid(), legend, e.getX(), e.getY(), e.getZ(), e.getYaw());
    }

    /** Cosmetic armor preset per style. NATURAL = no armor (just the head). */
    private static void equipStyleArmor(ArmorStandEntity stand, StatueStyle style) {
        switch (style) {
            case STONE -> equip(stand, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
            case GOLD -> equip(stand, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS);
            case EMERALD -> equip(stand, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
            case DIAMOND -> equip(stand, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
            case AMETHYST -> equip(stand, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
            case COPPER -> equip(stand, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS);
            case NETHERITE -> equip(stand, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);
            case OBSIDIAN -> equip(stand, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);
            case CRYSTAL -> equip(stand, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
            case GLASS -> { /* head only, body invisible — looks like a floating ghostly figure */ stand.setInvisible(true); }
            case FROST -> equip(stand, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, null);
            case SOUL -> equip(stand, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, null);
            case NATURAL -> { /* head only */ }
        }
    }

    private static void equip(ArmorStandEntity stand, net.minecraft.item.Item chest,
                                 net.minecraft.item.Item legs, net.minecraft.item.Item feet) {
        if (chest != null) stand.equipStack(EquipmentSlot.CHEST, new ItemStack(chest));
        if (legs != null) stand.equipStack(EquipmentSlot.LEGS, new ItemStack(legs));
        if (feet != null) stand.equipStack(EquipmentSlot.FEET, new ItemStack(feet));
    }

    /**
     * Spawn one Pokémon via Cobblemon's /spawnpokemonat command, then locate
     * the resulting entity by spatial+temporal heuristic and apply our tags + NoAI.
     * <p>
     * {@code caster} MUST be a non-null ServerPlayerEntity — Cobblemon's spawn
     * command (1.6.1) calls {@code source.getPlayer().getUuid()} and NPEs if
     * the dispatched source has no associated player. Fail-fast: returns false
     * if caster is null instead of dispatching a doomed command.
     */
    private boolean spawnPokemonStatue(ServerWorld world, LegendEntry legend, int slot,
                                         Vec3d pos, float yaw, StatueStyle style, double scale,
                                         ServerPlayerEntity caster) {
        LegendEntry.TeamMember m = legend.team().get(slot);
        if (m.species() == null || m.species().isEmpty()) return false;

        // Capture pre-spawn Cobblemon entity uuids so we can identify the new one
        Set<UUID> before = new HashSet<>();
        for (Entity e : world.iterateEntities()) {
            if (isCobblemonPokemon(e)) before.add(e.getUuid());
        }

        StringBuilder props = new StringBuilder(m.species().toLowerCase(Locale.ROOT));
        if (m.level() > 0) props.append(" level=").append(m.level());
        if (m.shiny()) props.append(" shiny");
        if (m.form() != null && !m.form().isBlank()) props.append(' ').append(m.form());
        // Add cosmetic aspects too (regional, gigantamax, etc.) but skip duplicates
        if (m.aspects() != null) {
            for (String a : m.aspects()) {
                if (a == null || a.isBlank()) continue;
                if (a.equalsIgnoreCase(m.form())) continue;
                if (a.equalsIgnoreCase("shiny")) continue;
                props.append(' ').append(a);
            }
        }

        // Cobblemon 1.6.1: /spawnpokemonat <pos> <properties>. Position comes
        // BEFORE properties. The arg type is BlockPosArgument (integer coords),
        // not Vec3 — decimals get truncated by the parser anyway, so use ints.
        int ix = (int) Math.floor(pos.x);
        int iy = (int) Math.floor(pos.y);
        int iz = (int) Math.floor(pos.z);
        String cmd = String.format(Locale.ROOT, "spawnpokemonat %d %d %d %s", ix, iy, iz, props);

        if (caster == null || caster.networkHandler == null) {
            LOGGER.warn("[CobbleCap] No valid caster for spawnpokemonat — skipping '{}'", cmd);
            return false;
        }
        try {
            var dispatcher = server.getCommandManager().getDispatcher();
            // CRITICAL: Cobblemon's spawn command NPEs if source.getPlayer() is null.
            // Use the caster's command source (player-bound) instead of the bare
            // server source. caster.getCommandSource() carries the player binding.
            var source = caster.getCommandSource()
                    .withSilent()
                    .withWorld(world)
                    .withPosition(pos);
            int result = dispatcher.execute(cmd, source);
            if (result == 0) {
                LOGGER.warn("[CobbleCap] /spawnpokemonat returned 0 for: {}", cmd);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] /spawnpokemonat failed for '{}': {}", cmd, e.getMessage());
            return false;
        }

        // Locate the new entity: an untagged Cobblemon Pokémon within 4 blocks
        Box around = new Box(pos.x - 4, pos.y - 4, pos.z - 4, pos.x + 4, pos.y + 4, pos.z + 4);
        Entity newEntity = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : world.iterateEntities()) {
            if (!isCobblemonPokemon(e)) continue;
            if (before.contains(e.getUuid())) continue;
            if (!e.getBoundingBox().intersects(around)) continue;
            if (e.getCommandTags().contains(TAG_MASTER)) continue;
            double dx = e.getX() - pos.x, dy = e.getY() - pos.y, dz = e.getZ() - pos.z;
            double d2 = dx*dx + dy*dy + dz*dz;
            if (d2 < bestDist) { bestDist = d2; newEntity = e; }
        }
        if (newEntity == null) {
            LOGGER.warn("[CobbleCap] could not locate spawned Cobblemon entity for '{}'", m.species());
            return false;
        }

        // Apply NoAI / Silent / NoGravity / Invulnerable / Persistent + tags
        try {
            newEntity.setSilent(true);
            newEntity.setInvulnerable(true);    // blocks damage
            newEntity.setNoGravity(true);       // keep Pokémon floating wherever placed
            if (newEntity instanceof net.minecraft.entity.mob.MobEntity mob) {
                mob.setAiDisabled(true);
                mob.setPersistent();
            }
        } catch (Exception ex) {
            LOGGER.debug("[CobbleCap] pokemon flags partially applied: {}", ex.getMessage());
        }

        // Make the statue non-interactive: not capturable, not battleable.
        // Both flags are first-class in Cobblemon 1.6.1 — capture checks
        // Pokemon.isUncatchable() (driven by UncatchableProperty in custom
        // properties); battle start checks PokemonEntity.UNBATTLEABLE
        // (TrackedData<Boolean>). Apply via reflection to keep Cobblemon as
        // compileOnly dep (direct typed import fails due to missing yarn
        // superclasses on the classpath).
        applyStatueInertia(newEntity);
        newEntity.addCommandTag(TAG_MASTER);
        if (legend.isTest()) newEntity.addCommandTag(TAG_TEST);
        newEntity.addCommandTag(TAG_LEGEND_PREFIX + legend.playerName().toLowerCase(Locale.ROOT));
        newEntity.addCommandTag(TAG_SLOT_PREFIX + slot);
        newEntity.addCommandTag(TAG_STYLE_PREFIX + style.key());

        // Face the same direction as the player statue
        newEntity.setYaw(yaw);
        newEntity.setHeadYaw(yaw);
        newEntity.setBodyYaw(yaw);

        // Set nickname if any
        if (m.nickname() != null && !m.nickname().isBlank()) {
            newEntity.setCustomName(Text.literal(m.nickname()).formatted(Formatting.AQUA));
            newEntity.setCustomNameVisible(true);
        }

        // Pokémon-side scale: try Cobblemon's scaleModifier property via reflection
        applyPokemonScale(newEntity, scale);
        return true;
    }

    /**
     * Make a Cobblemon PokemonEntity totally inert (statue mode):
     *   - {@code UNBATTLEABLE} DataTracker flag set true → right-click does
     *     NOT start a battle (checked in PokemonEntity.canBattle).
     *   - {@code UncatchableProperty} applied to the underlying Pokemon →
     *     Pokéballs bounce off (checked in PokeBallItem capture flow).
     * Damage immunity is already handled by setInvulnerable(true) earlier.
     * Pure reflection — Cobblemon classes are not on our compile-time path.
     */
    private static void applyStatueInertia(Entity entity) {
        // 1. UNBATTLEABLE via DataTracker — scan PokemonEntity class for the
        //    public static TrackedData<Boolean> field whose name contains "BATTLEABLE".
        try {
            Class<?> entityClass = entity.getClass();
            // Walk up to com.cobblemon.mod.common.entity.pokemon.PokemonEntity
            for (java.lang.reflect.Field f : entityClass.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().getSimpleName().equals("TrackedData")) continue;
                String n = f.getName();
                if (!n.contains("BATTLEABLE") && !n.contains("UNBATTLE")) continue;
                Object trackedData = f.get(null);
                if (trackedData == null) continue;
                // Find DataTracker.set(TrackedData, Object) — vanilla MC API
                java.lang.reflect.Method getDt = entity.getClass().getMethod("getDataTracker");
                Object dataTracker = getDt.invoke(entity);
                for (java.lang.reflect.Method m : dataTracker.getClass().getMethods()) {
                    if (!m.getName().equals("set")) continue;
                    if (m.getParameterCount() != 2) continue;
                    try {
                        m.invoke(dataTracker, trackedData, Boolean.TRUE);
                        LOGGER.debug("[CobbleCap] Statue: set DataTracker field {} = true", n);
                        break;
                    } catch (Exception ignored) {}
                }
                break;
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] Statue UNBATTLEABLE setup failed: {}", e.getMessage());
        }

        // 2. UNCATCHABLE via UncatchableProperty.INSTANCE.uncatchable().apply(pokemon)
        try {
            Object pokemon = null;
            for (java.lang.reflect.Method m : entity.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getName().equals("getPokemon")) continue;
                if (m.getReturnType().getSimpleName().equals("Pokemon")) {
                    pokemon = m.invoke(entity); break;
                }
            }
            if (pokemon != null) {
                Class<?> uncCls = Class.forName(
                        "com.cobblemon.mod.common.pokemon.properties.UncatchableProperty");
                Object instance = uncCls.getField("INSTANCE").get(null);
                // Call .uncatchable() to obtain a FlagProperty instance
                Object flagProperty = uncCls.getMethod("uncatchable").invoke(instance);
                // Call .apply(pokemon) on the FlagProperty
                java.lang.reflect.Method applyMethod = null;
                for (java.lang.reflect.Method m : flagProperty.getClass().getMethods()) {
                    if (!m.getName().equals("apply")) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0].getSimpleName().equals("Pokemon")) {
                        applyMethod = m; break;
                    }
                }
                if (applyMethod != null) {
                    applyMethod.invoke(flagProperty, pokemon);
                    LOGGER.debug("[CobbleCap] Statue: applied UncatchableProperty to Pokemon");
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] Statue UNCATCHABLE setup failed: {}", e.getMessage());
        }
    }

    /**
     * Apply a scale factor on a Cobblemon entity by reflection. Method names are
     * INTERMEDIARY at runtime — never call getMethod("yarnName"). Instead, find
     * the getter that returns an object whose class is named "Pokemon", then
     * locate a single-float setter named *Scale* on that returned object.
     */
    private static void applyPokemonScale(Entity entity, double scale) {
        if (scale == 1.0) return;
        try {
            Object pokemon = null;
            for (Method m : entity.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt == null || rt == Void.TYPE) continue;
                if (!rt.getSimpleName().equals("Pokemon")) continue;
                try { pokemon = m.invoke(entity); if (pokemon != null) break; }
                catch (Exception ignored) {}
            }
            if (pokemon == null) return;
            for (Method m : pokemon.getClass().getMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!(n.contains("scale") && (n.startsWith("set") || n.contains("scalemod")))) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                try {
                    if (p == float.class || p == Float.class) {
                        m.invoke(pokemon, (float) scale); return;
                    } else if (p == double.class || p == Double.class) {
                        m.invoke(pokemon, scale); return;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * Apply a uniform scale via the vanilla {@code minecraft:generic.scale} attribute.
     * Only one mechanism is used (no NBT Small flag) to avoid double-scaling.
     */
    private static boolean applyScale(Entity entity, double scale) {
        if (!(entity instanceof LivingEntity le)) return true;
        try {
            var id = net.minecraft.util.Identifier.of("minecraft", "generic.scale");
            java.util.Optional<? extends net.minecraft.registry.entry.RegistryEntry<?>> opt =
                    net.minecraft.registry.Registries.ATTRIBUTE.getEntry(id);
            if (opt.isPresent()) {
                @SuppressWarnings("unchecked")
                var entry = (net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute>) opt.get();
                var inst = le.getAttributeInstance(entry);
                if (inst != null) inst.setBaseValue(scale);
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] applyScale failed: {}", e.getMessage());
        }
        // Also propagate to the doppelganger if there's one for this entity
        // (player statues have an overlay doppel that scales independently of
        // the marker armor stand).
        try {
            ChampionDoppelganger d = CobbleCapMod.INSTANCE.getStatueManager().doppels.get(entity.getUuid());
            if (d != null) d.setScale(scale);
        } catch (Exception ignored) {}
        return true;
    }

    /** Read the generic.scale attribute base value (1.0 default). */
    private static double readScaleAttribute(LivingEntity le) {
        try {
            var id = net.minecraft.util.Identifier.of("minecraft", "generic.scale");
            var opt = net.minecraft.registry.Registries.ATTRIBUTE.getEntry(id);
            if (opt.isEmpty()) return 1.0;
            @SuppressWarnings("unchecked")
            var entry = (net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute>) opt.get();
            var inst = le.getAttributeInstance(entry);
            return inst != null ? inst.getBaseValue() : 1.0;
        } catch (Exception e) { return 1.0; }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static StatueStyle readStyle(Entity e) {
        for (String tag : e.getCommandTags()) {
            if (tag.startsWith(TAG_STYLE_PREFIX)) {
                return StatueStyle.fromKey(tag.substring(TAG_STYLE_PREFIX.length()));
            }
        }
        return StatueStyle.NATURAL;
    }

    private static String readLegendName(Entity e) {
        for (String tag : e.getCommandTags()) {
            if (tag.startsWith(TAG_LEGEND_PREFIX)) {
                return tag.substring(TAG_LEGEND_PREFIX.length());
            }
        }
        return null;
    }

    /** Forward vector from yaw in degrees, magnitude N. yaw=0 → -Z (south facing). */
    private static Vec3d forwardVec(float yaw, double magnitude) {
        double rad = Math.toRadians(yaw);
        return new Vec3d(-MathHelper.sin((float) rad) * magnitude, 0, MathHelper.cos((float) rad) * magnitude);
    }

    private static boolean isCobblemonPokemon(Entity e) {
        String typeId = net.minecraft.entity.EntityType.getId(e.getType()).toString();
        return typeId.equals("cobblemon:pokemon");
    }
}
