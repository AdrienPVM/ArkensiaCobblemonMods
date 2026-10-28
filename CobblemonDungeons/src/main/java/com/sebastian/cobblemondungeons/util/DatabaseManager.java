package com.sebastian.cobblemondungeons.util;

import com.sebastian.cobblemondungeons.CobblemonDungeons;
import com.sebastian.cobblemondungeons.config.ConfigLoader;
import com.sebastian.cobblemondungeons.config.MainConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.UUID;

public class DatabaseManager {
    private static HikariDataSource dataSource;
    private static String tablePrefix;
    private static String lastError = null;

    public static void init() {
        MainConfig.DatabaseConfig dbConfig = ConfigLoader.getMainConfig().database;
        if (!dbConfig.useSql) {
            lastError = "SQL is disabled in config.json";
            return;
        }

        CobblemonDungeons.LOGGER.info("Initializing SQL Database connection...");
        tablePrefix = dbConfig.tablePrefix;

        try {
            HikariConfig config = new HikariConfig();
            // Use MariaDB driver but it's compatible with MySQL
            config.setJdbcUrl("jdbc:mariadb://" + dbConfig.host + ":" + dbConfig.port + "/" + dbConfig.database);
            config.setUsername(dbConfig.username);
            config.setPassword(dbConfig.password);
            config.setDriverClassName("org.mariadb.jdbc.Driver");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.setMaximumPoolSize(10);
            config.setConnectionTimeout(5000);

            dataSource = new HikariDataSource(config);

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_sync (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "data MEDIUMBLOB, " +
                        "last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
            }
            lastError = null;
            CobblemonDungeons.LOGGER.info("SQL Database initialized successfully.");
        } catch (Exception e) {
            CobblemonDungeons.LOGGER.error("Failed to initialize database. Player data transfer via SQL will be disabled.", e);
            lastError = e.getMessage();
            dataSource = null;
        }
    }

    public static boolean isEnabled() {
        return dataSource != null;
    }

    public static void savePlayerData(UUID uuid, NbtCompound data) {
        if (dataSource == null) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO " + tablePrefix + "player_sync (uuid, data) VALUES (?, ?) ON DUPLICATE KEY UPDATE data = VALUES(data)")) {
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(data, baos);
            
            ps.setString(1, uuid.toString());
            ps.setBytes(2, baos.toByteArray());
            ps.executeUpdate();
        } catch (Exception e) {
            CobblemonDungeons.LOGGER.error("Failed to save player data to database for " + uuid, e);
        }
    }

    public static NbtCompound loadPlayerData(UUID uuid) {
        if (dataSource == null) return null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT data FROM " + tablePrefix + "player_sync WHERE uuid = ?")) {
            
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] bytes = rs.getBytes("data");
                    return NbtIo.readCompressed(new ByteArrayInputStream(bytes), net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                }
            }
        } catch (Exception e) {
            CobblemonDungeons.LOGGER.error("Failed to load player data from database for " + uuid, e);
        }
        return null;
    }

    public static String testConnection() {
        MainConfig.DatabaseConfig dbConfig = ConfigLoader.getMainConfig().database;
        if (!dbConfig.useSql) return "\u00A7cSQL is disabled in config.json";
        if (dataSource == null) {
            return "\u00A7cSQL is enabled but the connection failed. Error: " + (lastError != null ? lastError : "Unknown error. Check logs/latest.log for details.");
        }

        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(5)) {
                return "\u00A7aSQL Connection is active and healthy! (Table prefix: " + tablePrefix + ")";
            } else {
                return "\u00A7cSQL Connection is invalid.";
            }
        } catch (Exception e) {
            return "\u00A7cSQL Connection test failed: " + e.getMessage();
        }
    }

    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
