package com.kyzer.adminpanel;

import org.bukkit.plugin.java.JavaPlugin;

public class AdminPanelPlugin extends JavaPlugin {

    private static AdminPanelPlugin instance;
    private BridgeClient bridgeClient;
    private FreezeManager freezeManager;
    private PlayerStateBroadcaster broadcaster;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.freezeManager = new FreezeManager();
        getServer().getPluginManager().registerEvents(freezeManager, this);

        String backendUrl = getConfig().getString("backend-url");
        String authToken = getConfig().getString("auth-token");
        String serverId = getConfig().getString("server-id", "default");
        long reconnectDelay = getConfig().getLong("reconnect-delay-ms", 5000);

        if (authToken == null || authToken.equals("CHANGE-ME-TO-A-LONG-RANDOM-SECRET")) {
            getLogger().warning("AdminPanel: auth-token is still the default placeholder! "
                    + "Set a real secret in config.yml before exposing this to a real backend.");
        }

        this.bridgeClient = new BridgeClient(backendUrl, authToken, serverId, reconnectDelay);
        bridgeClient.connectAsync();

        long heartbeatTicks = (getConfig().getLong("heartbeat-interval-ms", 5000) / 50); // ms -> ticks
        this.broadcaster = new PlayerStateBroadcaster(bridgeClient);
        broadcaster.runTaskTimer(this, 20L, Math.max(heartbeatTicks, 20L));

        // Live inventory-change hook: pushes an update the moment a player's inventory changes,
        // instead of waiting for the next heartbeat tick.
        getServer().getPluginManager().registerEvents(new InventoryChangeListener(bridgeClient), this);

        getLogger().info("AdminPanel enabled. Bridging to " + backendUrl);
    }

    @Override
    public void onDisable() {
        if (bridgeClient != null) {
            bridgeClient.shutdown();
        }
        getLogger().info("AdminPanel disabled.");
    }

    public static AdminPanelPlugin get() {
        return instance;
    }

    public FreezeManager getFreezeManager() {
        return freezeManager;
    }
}
