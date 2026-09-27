package com.kyzer.adminpanel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * Persistent outbound WebSocket connection: Plugin -> Backend.
 * Outbound-only by design so it works on hosts that don't allow inbound ports
 * (the common case for shared Minecraft hosting).
 */
public class BridgeClient {

    private final String url;
    private final String authToken;
    private final String serverId;
    private final long reconnectDelayMs;
    private WebSocketClient client;
    private volatile boolean shuttingDown = false;

    public BridgeClient(String url, String authToken, String serverId, long reconnectDelayMs) {
        this.url = url;
        this.authToken = authToken;
        this.serverId = serverId;
        this.reconnectDelayMs = reconnectDelayMs;
    }

    public void connectAsync() {
        try {
            client = new WebSocketClient(new URI(url)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    JsonObject auth = new JsonObject();
                    auth.addProperty("type", "auth");
                    auth.addProperty("token", authToken);
                    auth.addProperty("serverId", serverId);
                    send(auth.toString());
                    AdminPanelPlugin.get().getLogger().info("AdminPanel: connected to backend.");
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                        // All Bukkit API calls MUST happen on the main server thread.
                        Bukkit.getScheduler().runTask(AdminPanelPlugin.get(), () ->
                                CommandDispatcher.handle(json, BridgeClient.this));
                    } catch (Exception e) {
                        AdminPanelPlugin.get().getLogger().warning("AdminPanel: bad message from backend: " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    AdminPanelPlugin.get().getLogger().warning("AdminPanel: backend connection closed (" + reason + "). Reconnecting...");
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    AdminPanelPlugin.get().getLogger().warning("AdminPanel: connection error: " + ex.getMessage());
                }
            };
            client.setConnectionLostTimeout(30);
            client.connect();
        } catch (Exception e) {
            AdminPanelPlugin.get().getLogger().severe("AdminPanel: failed to start connection: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (shuttingDown) return;
        Bukkit.getScheduler().runTaskLaterAsynchronously(AdminPanelPlugin.get(), this::connectAsync, reconnectDelayMs / 50);
    }

    public void send(JsonObject payload) {
        if (client != null && client.isOpen()) {
            client.send(payload.toString());
        }
    }

    public void shutdown() {
        shuttingDown = true;
        if (client != null) {
            client.close();
        }
    }
}
