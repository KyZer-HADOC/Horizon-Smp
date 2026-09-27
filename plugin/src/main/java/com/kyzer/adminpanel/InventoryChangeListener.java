package com.kyzer.adminpanel;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class InventoryChangeListener implements Listener {

    private final BridgeClient bridge;

    public InventoryChangeListener(BridgeClient bridge) {
        this.bridge = bridge;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            pushDelayed(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            pushDelayed(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        pushDelayed(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "player_left");
        payload.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        bridge.send(payload);
    }

    // Delay one tick so the inventory reflects the click's actual result before we snapshot it.
    private void pushDelayed(Player p) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) return;
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "player_update");
                payload.add("player", PlayerStateBroadcaster.detail(p));
                bridge.send(payload);
            }
        }.runTaskLater(AdminPanelPlugin.get(), 1L);
    }
}
