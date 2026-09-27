package com.kyzer.adminpanel;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

/**
 * Every case here is a REAL action against the live Paper server — nothing here
 * is cosmetic. If a player/target can't be found, we send an "error" reply back
 * to the dashboard instead of silently no-op'ing.
 */
public class CommandDispatcher {

    public static void handle(JsonObject msg, BridgeClient bridge) {
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        String requestId = msg.has("requestId") ? msg.get("requestId").getAsString() : null;

        try {
            switch (type) {
                case "get_players" -> sendPlayerList(bridge, requestId);
                case "get_player_detail" -> sendPlayerDetail(msg, bridge, requestId);
                case "give_item" -> giveItem(msg, bridge, requestId);
                case "set_slot" -> setSlot(msg, bridge, requestId);
                case "remove_slot" -> removeSlot(msg, bridge, requestId);
                case "clear_inventory" -> clearInventory(msg, bridge, requestId);
                case "kick" -> kick(msg, bridge, requestId);
                case "ban" -> ban(msg, bridge, requestId);
                case "unban" -> unban(msg, bridge, requestId);
                case "teleport" -> teleport(msg, bridge, requestId);
                case "set_gamemode" -> setGamemode(msg, bridge, requestId);
                case "set_op" -> setOp(msg, bridge, requestId);
                case "execute_command" -> executeCommand(msg, bridge, requestId);
                case "freeze" -> setFreeze(msg, bridge, requestId, true);
                case "unfreeze" -> setFreeze(msg, bridge, requestId, false);
                default -> ack(bridge, requestId, false, "Unknown command type: " + type);
            }
        } catch (Exception e) {
            ack(bridge, requestId, false, "Error: " + e.getMessage());
        }
    }

    private static Player findOnline(String uuidOrName) {
        Player p = null;
        try {
            p = Bukkit.getPlayer(UUID.fromString(uuidOrName));
        } catch (IllegalArgumentException ignored) { /* not a UUID, try name */ }
        if (p == null) p = Bukkit.getPlayerExact(uuidOrName);
        return p;
    }

    private static void sendPlayerList(BridgeClient bridge, String requestId) {
        var arr = new com.google.gson.JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            arr.add(PlayerStateBroadcaster.summarize(p));
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "player_list");
        resp.addProperty("requestId", requestId);
        resp.add("players", arr);
        bridge.send(resp);
    }

    private static void sendPlayerDetail(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "player_detail");
        resp.addProperty("requestId", requestId);
        if (p == null) {
            resp.addProperty("ok", false);
            resp.addProperty("error", "Player not online");
        } else {
            resp.addProperty("ok", true);
            resp.add("player", PlayerStateBroadcaster.detail(p));
        }
        bridge.send(resp);
    }

    private static void giveItem(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        ItemStack item = ItemSerializer.fromJson(msg.getAsJsonObject("item"));
        if (msg.has("slot")) {
            p.getInventory().setItem(msg.get("slot").getAsInt(), item);
        } else {
            p.getInventory().addItem(item);
        }
        p.updateInventory();
        ack(bridge, requestId, true, null);
    }

    private static void setSlot(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        int slot = msg.get("slot").getAsInt();
        String invType = msg.has("inventoryType") ? msg.get("inventoryType").getAsString() : "main";
        ItemStack item = ItemSerializer.fromJson(msg.getAsJsonObject("item"));
        applyToSlot(p, invType, slot, item);
        p.updateInventory();
        ack(bridge, requestId, true, null);
    }

    private static void removeSlot(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        int slot = msg.get("slot").getAsInt();
        String invType = msg.has("inventoryType") ? msg.get("inventoryType").getAsString() : "main";
        applyToSlot(p, invType, slot, null);
        p.updateInventory();
        ack(bridge, requestId, true, null);
    }

    private static void applyToSlot(Player p, String invType, int slot, ItemStack item) {
        PlayerInventory inv = p.getInventory();
        switch (invType) {
            case "armor" -> {
                ItemStack[] armor = inv.getArmorContents();
                if (slot >= 0 && slot < armor.length) { armor[slot] = item; inv.setArmorContents(armor); }
            }
            case "offhand" -> inv.setItemInOffHand(item);
            case "enderchest" -> {
                ItemStack[] contents = p.getEnderChest().getContents();
                if (slot >= 0 && slot < contents.length) { contents[slot] = item; p.getEnderChest().setContents(contents); }
            }
            default -> inv.setItem(slot, item); // "main" covers hotbar(0-8) + main(9-35) in Bukkit's unified indexing
        }
    }

    private static void clearInventory(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        p.updateInventory();
        ack(bridge, requestId, true, null);
    }

    private static void kick(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        String reason = msg.has("reason") ? msg.get("reason").getAsString() : "Kicked by admin";
        p.kick(net.kyori.adventure.text.Component.text(reason));
        ack(bridge, requestId, true, null);
    }

    private static void ban(JsonObject msg, BridgeClient bridge, String requestId) {
        String name = msg.get("player").getAsString();
        String reason = msg.has("reason") ? msg.get("reason").getAsString() : "Banned by admin";
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(target.getName() != null ? target.getName() : name, reason, null, "AdminPanel");
        Player online = findOnline(name);
        if (online != null) online.kick(net.kyori.adventure.text.Component.text(reason));
        ack(bridge, requestId, true, null);
    }

    private static void unban(JsonObject msg, BridgeClient bridge, String requestId) {
        String name = msg.get("player").getAsString();
        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(name);
        ack(bridge, requestId, true, null);
    }

    private static void teleport(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        if (msg.has("targetPlayer")) {
            Player target = findOnline(msg.get("targetPlayer").getAsString());
            if (target == null) { ack(bridge, requestId, false, "Target player not online"); return; }
            p.teleport(target.getLocation());
        } else {
            double x = msg.get("x").getAsDouble();
            double y = msg.get("y").getAsDouble();
            double z = msg.get("z").getAsDouble();
            Location loc = new Location(p.getWorld(), x, y, z);
            p.teleport(loc);
        }
        ack(bridge, requestId, true, null);
    }

    private static void setGamemode(JsonObject msg, BridgeClient bridge, String requestId) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        p.setGameMode(GameMode.valueOf(msg.get("gamemode").getAsString().toUpperCase()));
        ack(bridge, requestId, true, null);
    }

    private static void setOp(JsonObject msg, BridgeClient bridge, String requestId) {
        String name = msg.get("player").getAsString();
        boolean op = msg.get("op").getAsBoolean();
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        target.setOp(op);
        ack(bridge, requestId, true, null);
    }

    private static void executeCommand(JsonObject msg, BridgeClient bridge, String requestId) {
        String command = msg.get("command").getAsString();
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        ack(bridge, requestId, ok, ok ? null : "Command returned false");
    }

    private static void setFreeze(JsonObject msg, BridgeClient bridge, String requestId, boolean frozen) {
        Player p = findOnline(msg.get("player").getAsString());
        if (p == null) { ack(bridge, requestId, false, "Player not online"); return; }
        AdminPanelPlugin.get().getFreezeManager().setFrozen(p.getUniqueId(), frozen);
        ack(bridge, requestId, true, null);
    }

    private static void ack(BridgeClient bridge, String requestId, boolean ok, String error) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "ack");
        resp.addProperty("requestId", requestId);
        resp.addProperty("ok", ok);
        if (error != null) resp.addProperty("error", error);
        bridge.send(resp);
    }
}
