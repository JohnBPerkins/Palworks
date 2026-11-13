package dev.palworks.hunger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.palworks.Palworks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What can go in a trough, and how much fullness each item gives a worker that eats it.
 *
 * <ul>
 *   <li>The item tag {@code palworks:trough_food} says what a trough takes (tags reach the client,
 *       so its screen agrees with the server). By default: Cobblemon's berries and Aprijuices. Mochi is left out: it's for EV training, and a trough would waste it.</li>
 *   <li>Datapack files {@code data/<namespace>/palworks/trough_food/<name>.json} give how much
 *       fullness an item, or the items of a tag, give: {@code {"values": [{"item":
 *       "cobblemon:oran_berry", "fullness": 2}, {"tag": "cobblemon:berries", "fullness": 1}]}}. An
 *       item they don't name gives 1, as Cobblemon's berries, Mochi and Aprijuice do when
 *       hand-fed. Items named outright win over tags; between tags, the first file (by name) wins.</li>
 * </ul>
 */
public final class TroughFood {
    /** What a trough takes. */
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, Palworks.id("trough_food"));

    // The fields of a file's entries
    private static final String ITEM = "item";
    private static final String TAG_FIELD = "tag";
    private static final String FULLNESS = "fullness";

    /** The fullness an item gives if no file says otherwise. */
    public static final int DEFAULT_FULLNESS = 1;

    /** No values: everything gives {@link #DEFAULT_FULLNESS}. */
    public static final TroughFood EMPTY = new TroughFood(Map.of(), List.of());

    private static volatile TroughFood current = EMPTY;

    private final Map<ResourceLocation, Integer> items;
    private final List<TagValue> tags;

    private record TagValue(TagKey<Item> tag, int fullness) {}

    private TroughFood(Map<ResourceLocation, Integer> items, List<TagValue> tags) {
        this.items = items;
        this.tags = tags;
    }

    /** The values loaded from the datapacks. */
    public static TroughFood current() {
        return current;
    }

    /** Use these values (on load, and in tests). */
    public static void setCurrent(TroughFood values) {
        current = values;
    }

    /** Whether a trough takes this item. */
    public static boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && stack.is(TAG);
    }

    /** Whether a trough takes this item. */
    @SuppressWarnings("deprecation") // builtInRegistryHolder: the item's own holder, which tags are bound to
    public static boolean accepts(Item item) {
        return item.builtInRegistryHolder().is(TAG);
    }

    /** The fullness one of these gives a worker that eats it from a trough. */
    public int fullnessOf(ItemStack stack) {
        Integer named = items.get(
                stack.getItemHolder().unwrapKey().map(key -> key.location()).orElse(null));
        if (named != null) {
            return named;
        }
        for (TagValue value : tags) {
            if (stack.is(value.tag())) {
                return value.fullness();
            }
        }
        return DEFAULT_FULLNESS;
    }

    /** How many values were loaded: items, then tags. */
    public int itemCount() {
        return items.size();
    }

    public int tagCount() {
        return tags.size();
    }

    /** The fullness this file names for an item outright, or null (for tests). */
    Integer valueForItem(ResourceLocation item) {
        return items.get(item);
    }

    /** The tags with values, in the order they're tried (for tests). */
    List<ResourceLocation> tagOrder() {
        List<ResourceLocation> order = new ArrayList<>(tags.size());
        for (TagValue value : tags) {
            order.add(value.tag().location());
        }
        return order;
    }

    /**
     * The values in these files, by file id. A bad entry is skipped with a warning; the rest of the
     * file still counts.
     */
    public static TroughFood fromFiles(Map<String, JsonElement> files, Consumer<String> warn) {
        Map<ResourceLocation, Integer> items = new HashMap<>();
        List<TagValue> tags = new ArrayList<>();
        // In order of file id, so which tag wins doesn't depend on load order
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            String fileId = file.getKey();
            if (!file.getValue().isJsonObject()
                    || !file.getValue().getAsJsonObject().has("values")
                    || !file.getValue().getAsJsonObject().get("values").isJsonArray()) {
                warn.accept(fileId + ": needs a \"values\" list");
                continue;
            }
            JsonArray values = file.getValue().getAsJsonObject().getAsJsonArray("values");
            for (int i = 0; i < values.size(); i++) {
                String where = fileId + " values[" + i + "]";
                if (!values.get(i).isJsonObject()) {
                    warn.accept(where + ": should be an object");
                    continue;
                }
                JsonObject entry = values.get(i).getAsJsonObject();
                Integer fullness = fullness(entry, where, warn);
                if (fullness == null) {
                    continue;
                }
                if (entry.has(ITEM) == entry.has(TAG_FIELD)) {
                    warn.accept(where + ": needs one of \"item\" or \"tag\"");
                    continue;
                }
                String idText = (entry.has(ITEM) ? entry.get(ITEM) : entry.get(TAG_FIELD)).getAsString();
                ResourceLocation id = ResourceLocation.tryParse(idText.startsWith("#") ? idText.substring(1) : idText);
                if (id == null) {
                    warn.accept(where + ": \"" + idText + "\" isn't a valid id");
                    continue;
                }
                if (entry.has(ITEM)) {
                    items.putIfAbsent(id, fullness);
                } else {
                    tags.add(new TagValue(TagKey.create(Registries.ITEM, id), fullness));
                }
            }
        }
        return new TroughFood(Collections.unmodifiableMap(items), Collections.unmodifiableList(tags));
    }

    private static Integer fullness(JsonObject entry, String where, Consumer<String> warn) {
        if (!entry.has(FULLNESS)
                || !entry.get(FULLNESS).isJsonPrimitive()
                || !entry.get(FULLNESS).getAsJsonPrimitive().isNumber()) {
            warn.accept(where + ": needs a \"fullness\" number");
            return null;
        }
        int fullness = entry.get(FULLNESS).getAsInt();
        if (fullness < 1 || fullness > 20) {
            warn.accept(where + ": \"fullness\" must be 1 to 20, not " + fullness);
            return null;
        }
        return fullness;
    }
}
