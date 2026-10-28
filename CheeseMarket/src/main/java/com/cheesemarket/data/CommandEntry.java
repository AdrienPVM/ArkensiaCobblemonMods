package com.cheesemarket.data;

import com.google.gson.JsonObject;

/**
 * Represents a server command to execute on purchase.
 * {player} in the command string is replaced by the buyer's name at runtime.
 * Example: { command: "lp user {player} parent add vip", count: 1 }
 */
public class CommandEntry {

    private String command;
    private int count;

    public CommandEntry(String command, int count) {
        this.command = command;
        this.count = Math.max(1, count);
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("command", command);
        o.addProperty("count", count);
        return o;
    }

    public static CommandEntry fromJson(JsonObject o) {
        String cmd = o.has("command") ? o.get("command").getAsString() : "";
        int c = o.has("count") ? o.get("count").getAsInt() : 1;
        return new CommandEntry(cmd, c);
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String getCommand()                { return command; }
    public void   setCommand(String command)  { this.command = command; }
    public int    getCount()                  { return count; }
    public void   setCount(int count)         { this.count = Math.max(1, count); }

    /** Display string for summaries. */
    public String display() {
        String short_cmd = command.length() > 40 ? command.substring(0, 37) + "..." : command;
        return short_cmd + (count > 1 ? " x" + count : "");
    }

    @Override
    public String toString() { return command + " x" + count; }
}
