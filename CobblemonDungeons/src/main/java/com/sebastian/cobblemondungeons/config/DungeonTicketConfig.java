package com.sebastian.cobblemondungeons.config;

import java.util.ArrayList;
import java.util.List;

public class DungeonTicketConfig {
    public List<TicketConfig> tickets = new ArrayList<>();

    public DungeonTicketConfig() {
    }

    public static class TicketConfig {
        public String dungeonId = "generic_dungeon";
        public String itemName = "\u00A76Copper Dungeon Ticket";
        public int tier = 1;
    }
}
