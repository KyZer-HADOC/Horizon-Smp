package com.kyzer.adminpanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;

import java.util.Map;

/**
 * Converts between Bukkit ItemStacks and the JSON shape the web dashboard speaks.
 * This is the "give it teeth" piece: it's what lets an admin build an item in the
 * browser (name, lore, enchants, custom model data, potion type, damage) and have
 * it materialize as a real ItemStack in a player's real inventory.
 */
public class ItemSerializer {

    /** ItemStack -> JSON, for sending live inventory contents to the dashboard. */
    public static JsonObject toJson(ItemStack item) {
        JsonObject obj = new JsonObject();
        if (item == null || item.getType() == Material.AIR) {
            obj.addProperty("id", "minecraft:air");
            obj.addProperty("amount", 0);
            return obj;
        }

        obj.addProperty("id", item.getType().getKey().toString());
        obj.addProperty("amount", item.getAmount());

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();

            if (meta.hasDisplayName()) {
                obj.addProperty("name", meta.getDisplayName());
            }
            if (meta.hasLore()) {
                JsonArray lore = new JsonArray();
                meta.getLore().forEach(lore::add);
                obj.add("lore", lore);
            }
            if (meta.hasCustomModelData()) {
                obj.addProperty("customModelData", meta.getCustomModelData());
            }
            if (meta instanceof Damageable dmg && dmg.hasDamage()) {
                obj.addProperty("damage", dmg.getDamage());
                obj.addProperty("maxDurability", item.getType().getMaxDurability());
            }
            if (!meta.getEnchants().isEmpty()) {
                JsonArray enchants = new JsonArray();
                for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                    JsonObject enchObj = new JsonObject();
                    enchObj.addProperty("id", e.getKey().getKey().toString());
                    enchObj.addProperty("level", e.getValue());
                    enchants.add(enchObj);
                }
                obj.add("enchantments", enchants);
            }
            if (meta instanceof PotionMeta potionMeta) {
                JsonObject potion = new JsonObject();
                PotionData data = potionMeta.getBasePotionData();
                potion.addProperty("type", data.getType().name());
                potion.addProperty("upgraded", data.isUpgraded());
                potion.addProperty("extended", data.isExtended());
                obj.add("potion", potion);
            }
        }
        return obj;
    }

    /** JSON (from the dashboard's item browser) -> a real ItemStack. */
    @SuppressWarnings("deprecation")
    public static ItemStack fromJson(JsonObject obj) {
        String idStr = obj.get("id").getAsString().replace("minecraft:", "");
        Material material = Material.matchMaterial(idStr);
        if (material == null) {
            material = Material.STONE; // safe fallback rather than throwing
        }
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : 1;
        ItemStack item = new ItemStack(material, Math.max(1, amount));

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (obj.has("name") && !obj.get("name").getAsString().isBlank()) {
            meta.setDisplayName(obj.get("name").getAsString());
        }
        if (obj.has("lore")) {
            JsonArray loreArr = obj.getAsJsonArray("lore");
            java.util.List<String> lore = new java.util.ArrayList<>();
            loreArr.forEach(el -> lore.add(el.getAsString()));
            meta.setLore(lore);
        }
        if (obj.has("customModelData")) {
            meta.setCustomModelData(obj.get("customModelData").getAsInt());
        }
        if (obj.has("damage") && meta instanceof Damageable dmg) {
            dmg.setDamage(obj.get("damage").getAsInt());
        }
        if (obj.has("enchantments")) {
            for (var el : obj.getAsJsonArray("enchantments")) {
                JsonObject e = el.getAsJsonObject();
                String key = e.get("id").getAsString().replace("minecraft:", "");
                int level = e.get("level").getAsInt();
                Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(key));
                if (ench != null) {
                    meta.addEnchant(ench, level, true);
                }
            }
        }
        if (obj.has("potion") && meta instanceof PotionMeta potionMeta) {
            JsonObject potion = obj.getAsJsonObject("potion");
            try {
                PotionType type = PotionType.valueOf(potion.get("type").getAsString());
                boolean upgraded = potion.has("upgraded") && potion.get("upgraded").getAsBoolean();
                boolean extended = potion.has("extended") && potion.get("extended").getAsBoolean();
                potionMeta.setBasePotionData(new PotionData(type, extended, upgraded));
            } catch (IllegalArgumentException ignored) {
                // unknown potion type name from the client — leave meta as-is rather than crash
            }
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }
}
