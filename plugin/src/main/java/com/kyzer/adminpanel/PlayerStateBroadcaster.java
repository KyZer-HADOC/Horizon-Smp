package com.kyzer.adminpanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerStateBroadcaster extends BukkitRunnable {

    private final BridgeClient bridge;

    public PlayerStateBroadcaster(BridgeClient bridge) {
        this.bridge = bridge;
    }

    @Override
    public void run() {
        JsonArray arr = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            arr.add(summarize(p));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "heartbeat");
        payload.add("players", arr);
        bridge.send(payload);
    }

    /** Lightweight per-player summary for the player list view. */
    public static JsonObject summarize(Player p) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", p.getUniqueId().toString());
        o.addProperty("name", p.getName());
        o.addProperty("health", p.getHealth());
        o.addProperty("maxHealth", p.getAttribute(Attribute.MAX_HEALTH) != null
                ? p.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0);
        o.addProperty("food", p.getFoodLevel());
        o.addProperty("level", p.getLevel());
        o.addProperty("exp", p.getExp());
        o.addProperty("gamemode", p.getGameMode().name());
        o.addProperty("world", p.getWorld().getName());
        o.addProperty("x", p.getLocation().getX());
        o.addProperty("y", p.getLocation().getY());
        o.addProperty("z", p.getLocation().getZ());
        o.addProperty("frozen", AdminPanelPlugin.get().getFreezeManager().isFrozen(p.getUniqueId()));
        o.addProperty("op", p.isOp());
        return o;
    }

    /** Full detail including inventory/armor/offhand/ender chest, for the inventory editor. */
    public static JsonObject detail(Player p) {
        JsonObject o = summarize(p);
        PlayerInventory inv = p.getInventory();

        JsonArray main = new JsonArray();
        // Bukkit's unified inventory: slots 0-8 hotbar, 9-35 main storage
        for (int i = 0; i < 36; i++) {
            main.add(ItemSerializer.toJson(inv.getItem(i)));
        }
        o.add("mainInventory", main);

        JsonArray armor = new JsonArray();
        for (ItemStack piece : inv.getArmorContents()) {
            armor.add(ItemSerializer.toJson(piece));
        }
        o.add("armor", armor);

        o.add("offhand", ItemSerializer.toJson(inv.getItemInOffHand()));

        JsonArray enderChest = new JsonArray();
        for (ItemStack item : p.getEnderChest().getContents()) {
            enderChest.add(ItemSerializer.toJson(item));
        }
        o.add("enderChest", enderChest);

        return o;
    }
}
