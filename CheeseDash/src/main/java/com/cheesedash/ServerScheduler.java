package com.cheesedash;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages server restart/stop/maintenance scheduling.
 *
 * Supports:
 *  - One-shot actions with countdown warnings in MC chat + WS broadcast
 *  - Recurring schedules (daily/weekly at specific times)
 *  - Persistence of recurring schedules to disk
 *
 * Countdown warnings at: 30m, 15m, 10m, 5m, 3m, 2m, 1m, 30s, 10s, 5s
 */
public class ServerScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Warning thresholds in seconds
    private static final int[] WARNING_THRESHOLDS = {
        1800, 900, 600, 300, 180, 120, 60, 30, 10, 5
    };

    private final ScheduledExecutorService executor;
    private final Path configPath;
    private MinecraftServer server;

    // Active one-shot action (only one at a time)
    private volatile ScheduledAction activeAction;
    private ScheduledFuture<?> countdownFuture;

    // Recurring schedules
    private final List<RecurringSchedule> recurring = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> recurringCheckFuture;

    // ── Data classes ────────────────────────────────────────────────────────

    public enum ActionType {
        RESTART, STOP, MAINTENANCE
    }

    public static class ScheduledAction {
        public final String id;
        public final ActionType type;
        public final String message;
        public final long scheduledAt;   // epoch ms when action fires
        public final long createdAt;     // epoch ms
        public final String createdBy;   // username

        public ScheduledAction(ActionType type, long delaySeconds, String message, String createdBy) {
            this.id = "sa_" + Integer.toHexString(new Random().nextInt(0xFFFFFF));
            this.type = type;
            this.message = message != null ? message : "";
            this.createdAt = System.currentTimeMillis();
            this.scheduledAt = this.createdAt + delaySeconds * 1000;
            this.createdBy = createdBy != null ? createdBy : "system";
        }

        public long getRemainingSeconds() {
            return Math.max(0, (scheduledAt - System.currentTimeMillis()) / 1000);
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("type", type.name());
            obj.addProperty("message", message);
            obj.addProperty("scheduledAt", scheduledAt);
            obj.addProperty("createdAt", createdAt);
            obj.addProperty("createdBy", createdBy);
            obj.addProperty("remainingSeconds", getRemainingSeconds());
            return obj;
        }
    }

    public static class RecurringSchedule {
        public String id;
        public ActionType type;
        public int hour;      // 0-23
        public int minute;    // 0-59
        public Set<Integer> daysOfWeek; // 1=Monday .. 7=Sunday (ISO)
        public String message;
        public boolean enabled;

        public RecurringSchedule() {
            this.id = "rec_" + Integer.toHexString(new Random().nextInt(0xFFFFFF));
            this.daysOfWeek = new HashSet<>();
            this.message = "";
            this.enabled = true;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("type", type.name());
            obj.addProperty("hour", hour);
            obj.addProperty("minute", minute);
            JsonArray days = new JsonArray();
            daysOfWeek.stream().sorted().forEach(days::add);
            obj.add("daysOfWeek", days);
            obj.addProperty("message", message);
            obj.addProperty("enabled", enabled);
            return obj;
        }

        public static RecurringSchedule fromJson(JsonObject obj) {
            RecurringSchedule s = new RecurringSchedule();
            if (obj.has("id")) s.id = obj.get("id").getAsString();
            s.type = ActionType.valueOf(obj.get("type").getAsString());
            s.hour = obj.get("hour").getAsInt();
            s.minute = obj.get("minute").getAsInt();
            if (obj.has("daysOfWeek")) {
                obj.getAsJsonArray("daysOfWeek").forEach(e -> s.daysOfWeek.add(e.getAsInt()));
            }
            s.message = obj.has("message") ? obj.get("message").getAsString() : "";
            s.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            return s;
        }
    }

    // ── Constructor & lifecycle ──────────────────────────────────────────────

    public ServerScheduler(Path configDir) {
        this.configPath = configDir.resolve("cheesedash_schedules.json");
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "CheeseDash-Scheduler");
            t.setDaemon(true);
            return t;
        });
        loadFromDisk();
        LOGGER.info("[CheeseDash] ServerScheduler initialized — {} recurring schedule(s)", recurring.size());
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
        // Start recurring check — every 30 seconds
        if (recurringCheckFuture != null) recurringCheckFuture.cancel(false);
        recurringCheckFuture = executor.scheduleAtFixedRate(this::checkRecurring, 30, 30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (countdownFuture != null) countdownFuture.cancel(true);
        if (recurringCheckFuture != null) recurringCheckFuture.cancel(true);
        executor.shutdownNow();
    }

    // ── One-shot scheduling ─────────────────────────────────────────────────

    /**
     * Schedule a one-shot server action (restart/stop/maintenance).
     * Only one active action at a time — cancels any existing.
     */
    public ScheduledAction scheduleAction(ActionType type, long delaySeconds, String message, String username) {
        // Cancel existing if any
        cancelAction();

        ScheduledAction action = new ScheduledAction(type, delaySeconds, message, username);
        this.activeAction = action;

        // Start countdown ticker (every second)
        countdownFuture = executor.scheduleAtFixedRate(() -> {
            try {
                tickCountdown();
            } catch (Exception e) {
                LOGGER.error("[CheeseDash] Countdown tick error: {}", e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Broadcast creation
        broadcastScheduleEvent("schedule_created", action);

        // Immediate warning if delay is short
        long remaining = action.getRemainingSeconds();
        String label = typeLabel(type);
        LOGGER.info("[CheeseDash] {} scheduled in {}s by {}: {}",
            label, remaining, username, message);

        // Send initial MC chat warning
        broadcastMcChat(type, remaining, message);

        return action;
    }

    /**
     * Cancel the active one-shot action.
     */
    public boolean cancelAction() {
        if (activeAction == null) return false;
        String label = typeLabel(activeAction.type);
        String typeLangKey = "cheesedash.scheduler.type." + activeAction.type.name().toLowerCase();
        LOGGER.info("[CheeseDash] {} cancelled (was scheduled for {})",
            label, activeAction.scheduledAt);

        activeAction = null;
        if (countdownFuture != null) {
            countdownFuture.cancel(false);
            countdownFuture = null;
        }

        // Broadcast cancellation
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "schedule_cancelled");
        DashboardRegistry.broadcast("schedule", msg.toString());

        // Notify MC chat
        if (server != null) {
            server.execute(() -> {
                server.getPlayerManager().broadcast(
                    Text.literal("\u00A7a[CheeseDash] \u00A7e").append(Text.translatable("cheesedash.scheduler.cancelled", Text.translatable(typeLangKey))), false);
            });
        }

        return true;
    }

    public ScheduledAction getActiveAction() {
        return activeAction;
    }

    public long getTimeRemaining() {
        return activeAction != null ? activeAction.getRemainingSeconds() : -1;
    }

    // ── Countdown logic ─────────────────────────────────────────────────────

    private void tickCountdown() {
        ScheduledAction action = this.activeAction;
        if (action == null) return;

        long remaining = action.getRemainingSeconds();

        // WS broadcast every 5 seconds (or every second in last 10s)
        if (remaining <= 10 || remaining % 5 == 0) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "schedule_tick");
            msg.addProperty("remaining", remaining);
            msg.addProperty("actionType", action.type.name());
            msg.addProperty("message", action.message);
            DashboardRegistry.broadcast("schedule", msg.toString());
        }

        // MC chat warnings at thresholds
        for (int threshold : WARNING_THRESHOLDS) {
            if (remaining == threshold) {
                broadcastMcChat(action.type, remaining, action.message);
                break;
            }
        }

        // Fire the action
        if (remaining <= 0) {
            fireAction(action);
        }
    }

    private void fireAction(ScheduledAction action) {
        // Stop the countdown ticker
        if (countdownFuture != null) {
            countdownFuture.cancel(false);
            countdownFuture = null;
        }
        activeAction = null;

        String label = typeLabel(action.type);
        LOGGER.info("[CheeseDash] {} firing now!", label);

        // WS broadcast
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "schedule_fired");
        msg.addProperty("actionType", action.type.name());
        DashboardRegistry.broadcast("schedule", msg.toString());

        // Discord event
        DiscordWebhookManager discord = CheeseDashMod.INSTANCE != null
            ? CheeseDashMod.INSTANCE.getDiscordWebhookManager() : null;

        if (server != null) {
            // Save all worlds BEFORE stopping — wait for completion (max 30s)
            CountDownLatch saveLatch = new CountDownLatch(1);
            server.execute(() -> {
                server.getPlayerManager().broadcast(
                    Text.literal("\u00A7e[CheeseDash] \u00A77").append(Text.translatable("cheesedash.scheduler.saving")), false);
                try {
                    server.save(false, true, true);
                    server.getPlayerManager().broadcast(
                        Text.literal("\u00A7a[CheeseDash] ").append(Text.translatable("cheesedash.scheduler.save_done")), false);
                } catch (Exception e) {
                    LOGGER.error("[CheeseDash] Save failed before {}: {}", label, e.getMessage());
                } finally {
                    saveLatch.countDown();
                }
            });
            try { saveLatch.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}

            // Discord notification
            if (action.type == ActionType.MAINTENANCE && discord != null) {
                Map<String, String> data = new HashMap<>();
                data.put("message", action.message);
                discord.sendEvent(DiscordWebhookManager.DiscordEventType.MAINTENANCE, data);
            }

            // For RESTART: spawn new server process before stopping current one
            if (action.type == ActionType.RESTART) {
                try {
                    restartProcess();
                } catch (Exception e) {
                    LOGGER.error("[CheeseDash] Failed to spawn restart process: {}", e.getMessage());
                }
            }

            // Stop the server
            server.execute(() -> {
                server.getPlayerManager().broadcast(
                    Text.literal("\u00A7c[CheeseDash] \u00A74").append(Text.translatable("cheesedash.scheduler.stopping")), false);
                server.stop(false);
            });
        }
    }

    /**
     * Spawn a new server process with the same JVM args, then let the current one stop.
     * Only called for RESTART — STOP and MAINTENANCE just exit.
     */
    private void restartProcess() throws Exception {
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        // Find server JAR from process args
        String jarPath = null;
        List<String> processArgs = ProcessHandle.current().info().arguments().map(List::of).orElse(List.of());
        for (int i = 0; i < processArgs.size() - 1; i++) {
            if (processArgs.get(i).equals("-jar")) { jarPath = processArgs.get(i + 1); break; }
        }
        // Fallback: scan working directory for fabric server JAR
        if (jarPath == null) {
            File workDir = new File(System.getProperty("user.dir"));
            File[] files = workDir.listFiles();
            if (files != null) for (File f : files) {
                String name = f.getName().toLowerCase();
                if (name.startsWith("fabric-server-launch") && name.endsWith(".jar")) { jarPath = f.getName(); break; }
                if (name.startsWith("server") && name.endsWith(".jar")) jarPath = f.getName();
            }
        }
        if (jarPath == null) throw new Exception("Cannot find server jar for restart");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jarPath);
        cmd.add("nogui");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.inheritIO();
        pb.start();
        LOGGER.info("[CheeseDash] Restart process spawned: {}", String.join(" ", cmd));
    }

    private void broadcastMcChat(ActionType type, long remainingSeconds, String message) {
        if (server == null) return;
        String typeLangKey = "cheesedash.scheduler.type." + type.name().toLowerCase();
        String timeStr = formatTime(remainingSeconds);
        String extra = (message != null && !message.isEmpty()) ? " \u00A77(" + message + ")" : "";

        server.execute(() -> {
            server.getPlayerManager().broadcast(
                Text.literal("\u00A7c[CheeseDash] \u00A7e")
                    .append(Text.translatable("cheesedash.scheduler.countdown",
                        Text.translatable(typeLangKey), "\u00A7c" + timeStr + "\u00A7e"))
                    .append(Text.literal(extra)), false);
        });
    }

    // ── Recurring schedules ─────────────────────────────────────────────────

    public RecurringSchedule addRecurring(ActionType type, int hour, int minute,
                                           Set<Integer> daysOfWeek, String message) {
        RecurringSchedule schedule = new RecurringSchedule();
        schedule.type = type;
        schedule.hour = hour;
        schedule.minute = minute;
        schedule.daysOfWeek = daysOfWeek != null ? daysOfWeek : new HashSet<>(Arrays.asList(1,2,3,4,5,6,7));
        schedule.message = message != null ? message : "";
        schedule.enabled = true;
        recurring.add(schedule);
        saveToDisk();
        return schedule;
    }

    public boolean removeRecurring(String id) {
        boolean removed = recurring.removeIf(s -> s.id.equals(id));
        if (removed) saveToDisk();
        return removed;
    }

    public boolean toggleRecurring(String id) {
        for (RecurringSchedule s : recurring) {
            if (s.id.equals(id)) {
                s.enabled = !s.enabled;
                saveToDisk();
                return true;
            }
        }
        return false;
    }

    public RecurringSchedule getRecurring(String id) {
        for (RecurringSchedule s : recurring) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public boolean updateRecurring(String id, JsonObject updates) {
        RecurringSchedule s = getRecurring(id);
        if (s == null) return false;
        if (updates.has("hour")) s.hour = updates.get("hour").getAsInt();
        if (updates.has("minute")) s.minute = updates.get("minute").getAsInt();
        if (updates.has("message")) s.message = updates.get("message").getAsString();
        if (updates.has("enabled")) s.enabled = updates.get("enabled").getAsBoolean();
        if (updates.has("type")) s.type = ActionType.valueOf(updates.get("type").getAsString());
        if (updates.has("daysOfWeek")) {
            s.daysOfWeek.clear();
            updates.getAsJsonArray("daysOfWeek").forEach(e -> s.daysOfWeek.add(e.getAsInt()));
        }
        saveToDisk();
        return true;
    }

    public List<RecurringSchedule> getRecurringSchedules() {
        return Collections.unmodifiableList(recurring);
    }

    /**
     * Called every 30 seconds to check if a recurring schedule should fire.
     * Fires by scheduling a one-shot action with a 5-minute countdown.
     */
    private void checkRecurring() {
        if (activeAction != null) return; // Don't overlap with an active countdown

        LocalDateTime now = LocalDateTime.now();
        int currentDay = now.getDayOfWeek().getValue(); // 1=Mon .. 7=Sun
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        for (RecurringSchedule schedule : recurring) {
            if (!schedule.enabled) continue;
            if (!schedule.daysOfWeek.contains(currentDay)) continue;

            // Check if we're within the fire window (±30 seconds of the scheduled time)
            // Since we check every 30s, this ensures we don't miss it
            if (currentHour == schedule.hour && currentMinute == schedule.minute) {
                // Fire with 5 minute countdown
                LOGGER.info("[CheeseDash] Recurring schedule '{}' triggered — scheduling {} in 5 minutes",
                    schedule.id, schedule.type);
                scheduleAction(schedule.type, 300, schedule.message, "scheduler");

                // Disable briefly to prevent re-firing within the same minute
                // (the 30s check interval could fire twice within the same minute)
                schedule.enabled = false;
                executor.schedule(() -> {
                    schedule.enabled = true;
                }, 90, TimeUnit.SECONDS);

                break;
            }
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void loadFromDisk() {
        if (!Files.exists(configPath)) return;
        try {
            String raw = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("recurring")) {
                root.getAsJsonArray("recurring").forEach(el -> {
                    try {
                        recurring.add(RecurringSchedule.fromJson(el.getAsJsonObject()));
                    } catch (Exception e) {
                        LOGGER.warn("[CheeseDash] Skipping invalid recurring schedule: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.warn("[CheeseDash] Failed to load schedules: {}", e.getMessage());
        }
    }

    private void saveToDisk() {
        Thread writer = new Thread(() -> {
            try {
                JsonObject root = new JsonObject();
                JsonArray arr = new JsonArray();
                for (RecurringSchedule s : recurring) {
                    arr.add(s.toJson());
                }
                root.add("recurring", arr);
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath, GSON.toJson(root));
            } catch (IOException e) {
                LOGGER.warn("[CheeseDash] Failed to save schedules: {}", e.getMessage());
            }
        }, "CheeseDash-ScheduleSave");
        writer.setDaemon(true);
        writer.start();
    }

    // ── WS broadcast helper ─────────────────────────────────────────────────

    private void broadcastScheduleEvent(String type, ScheduledAction action) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.add("action", action.toJson());
        DashboardRegistry.broadcast("schedule", msg.toString());
    }

    // ── Formatting helpers ──────────────────────────────────────────────────

    private static String typeLabel(ActionType type) {
        // These are used in log messages (not player-facing) — keep as literals for logs
        switch (type) {
            case RESTART: return "Restart";
            case STOP: return "Stop";
            case MAINTENANCE: return "Maintenance";
            default: return type.name();
        }
    }

    static String formatTime(long seconds) {
        if (seconds >= 3600) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            return h + "h" + (m > 0 ? String.format("%02dm", m) : "");
        } else if (seconds >= 60) {
            long m = seconds / 60;
            long s = seconds % 60;
            return m + " minute" + (m > 1 ? "s" : "") + (s > 0 ? " " + s + "s" : "");
        } else {
            return seconds + " seconde" + (seconds > 1 ? "s" : "");
        }
    }
}
