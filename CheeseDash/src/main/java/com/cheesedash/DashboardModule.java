package com.cheesedash;

import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * Public interface for CheeseDash integration.
 * Any mod wanting to appear in the dashboard implements this interface
 * and declares it as a "cheesedash" entrypoint in its fabric.mod.json.
 */
public interface DashboardModule {

    /** Route prefix. E.g. "market" → /api/v1/market/* */
    String getPrefix();

    /** Display name in the sidebar. E.g. "CheeseMarket" */
    String getDisplayName();

    /** Emoji icon for the sidebar. E.g. "🛒" */
    String getNavIcon();

    /** Short description. E.g. "Gestion des boutiques" */
    String getNavDescription();

    /** Handle all requests to /api/v1/<prefix>/<subRoute> */
    void handleRequest(HttpExchange exchange, String subRoute) throws IOException;

    /** Called when the MC server starts — store the server reference here. */
    default void onServerStart(MinecraftServer server) {}

    /** Called when the MC server stops. */
    default void onServerStop() {}

    /**
     * If non-null, CheeseDash registers a public HTTP context at /{prefix}/
     * serving static resources from classpath and delegating API calls
     * to {@link #handlePublicRequest}. No authentication required.
     */
    default String getPublicPrefix() { return null; }

    /** Handle unauthenticated requests under the public context /{publicPrefix}/api/*. */
    default void handlePublicRequest(HttpExchange exchange, String path) throws IOException {}
}
