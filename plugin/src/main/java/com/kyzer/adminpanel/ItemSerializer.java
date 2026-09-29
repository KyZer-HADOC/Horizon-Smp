package com.kyzer.adminpanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts between Bukkit ItemStacks and the JSON shape the web dashboard speaks.
 *
 * Written against Paper 26.1.2's current (non-deprecated) API:
 *  - display name / lore go through Adventure Components (customName()/lore()),
 *    not the old String-based setDisplayName()/setLore() pair.
 *  - potion base type goes through setBasePotionType()/getBasePotionType() —
 *    the old PotionData(type, extended, upgraded) class is gone. "Long"/"Strong"
 *    variants are now just their own PotionType values (e.g. LONG_SWIFTNESS),
 *    so the dashboard just sends that full type name as a string.
 *
 * Every item also carries a "raw" exact-copy blob (see toJson/fromJson) so moving an
 * item around the dashboard never loses data that the field-by-field JSON can't express.
 *
 * Names/lore round-trip as plain text (formatting/color codes are dropped on the
 * way to JSON and back) — good enough for admin tooling; say the word if you want
 * MiniMessage-formatted names preserved instead.
 */
public class ItemSerializer {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

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

        // Exact byte-for-byte copy of the item (all components/NBT, other plugins' data included).
        // The dashboard sends this back untouched when an item is just moved, so nothing is lost.
        try {
            obj.addProperty("raw", java.util.Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        } catch (Exception ignored) { /* fall back to the field-by-field data below */ }

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();

            if (meta.hasCustomName()) {
                obj.addProperty("name", PLAIN.serialize(meta.customName()));
            } else if (meta.hasDisplayName()) {
                obj.addProperty("name", PLAIN.serialize(meta.displayName()));
            }
            if (meta.hasLore()) {
                JsonArray lore = new JsonArray();
                for (Component line : meta.lore()) lore.add(PLAIN.serialize(line));
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
            if (meta instanceof PotionMeta potionMeta && potionMeta.hasBasePotionType()) {
                JsonObject potion = new JsonObject();
                potion.addProperty("type", potionMeta.getBasePotionType().name());
                obj.add("potion", potion);
            }
        }
        return obj;
    }

    /** JSON (from the dashboard's item browser) -> a real ItemStack. */
    public static ItemStack fromJson(JsonObject obj) {
        if (obj.has("raw")) {
            try {
                ItemStack exact = ItemStack.deserializeBytes(
                        java.util.Base64.getDecoder().decode(obj.get("raw").getAsString()));
                if (obj.has("amount")) {
                    exact.setAmount(Math.max(1, Math.min(obj.get("amount").getAsInt(), exact.getMaxStackSize())));
                }
                return exact;
            } catch (Exception ignored) { /* corrupt/foreign raw data — rebuild from the fields below */ }
        }
        String idStr = obj.get("id").getAsString().replace("minecraft:", "");
        Material material = Material.matchMaterial(idStr);
        if (material == null) {
            material = Material.STONE; // safe fallback rather than throwing
        }
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : 1;
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (obj.has("name") && !obj.get("name").getAsString().isBlank()) {
            meta.customName(Component.text(obj.get("name").getAsString()));
        }
        if (obj.has("lore")) {
            JsonArray loreArr = obj.getAsJsonArray("lore");
            List<Component> lore = new ArrayList<>();
            loreArr.forEach(el -> lore.add(Component.text(el.getAsString())));
            meta.lore(lore);
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
                PotionType type = PotionType.valueOf(potion.get("type").getAsString().toUpperCase());
                potionMeta.setBasePotionType(type);
            } catch (IllegalArgumentException ignored) {
                // unknown potion type name from the client — leave meta as-is rather than crash
            }
        }
        item.setItemMeta(meta);
        return item;
    }
}
