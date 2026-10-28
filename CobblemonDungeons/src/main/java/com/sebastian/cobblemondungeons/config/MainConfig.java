package com.sebastian.cobblemondungeons.config;

public class MainConfig {
    public DatabaseConfig database = new DatabaseConfig();

    public static class DatabaseConfig {
        public boolean useSql = false;
        public String host = "localhost";
        public int port = 3306;
        public String database = "minecraft";
        public String username = "root";
        public String password = "password";
        public String tablePrefix = "cobblemon_dungeons_";
    }
}
