package com.kyzer.adminpanel;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeManager implements Listener {

    private final Set<UUID> frozen = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void setFrozen(UUID uuid, boolean state) {
        if (state) frozen.add(uuid); else frozen.remove(uuid);
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            // Cancel actual movement but allow head rotation, so the player isn't just staring at a wall
            if (e.getFrom().distanceSquared(e.getTo()) > 0.0001) {
                e.setTo(e.getFrom());
            }
        }
    }
}
