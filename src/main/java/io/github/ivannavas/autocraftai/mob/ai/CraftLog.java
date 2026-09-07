package io.github.ivannavas.autocraftai.mob.ai;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The last few things the body actually made, so the overlay can show them happening.
 *
 * <p>Crafting is otherwise invisible: a number in the inventory changes and a rung quietly ticks over. A
 * strip of what was just made is the one part of the run a viewer can follow without reading the table.
 *
 * <h2>Two readers, two queues</h2>
 * The overlay wants the last few crafts and wants them to stay put; the reward wants the crafts that have
 * happened since it last asked and wants them gone once it has. Those are different needs, so they get
 * different queues off the same event rather than one queue with a cursor into it that both would have to
 * agree about.
 *
 * <h2>Textures are grabbed on the game thread, not served from it</h2>
 * The sprite for an item comes out of the resource pack, and the resource manager belongs to the client.
 * Reading it from an HTTP thread would be racing a resource reload for no good reason, so the bytes are
 * resolved once here — on the tick that records the craft — and the web server only ever reads the cache.
 */
@Slf4j
public final class CraftLog {

    /** Enough to fill a strip without it becoming a scrolling log. */
    private static final int KEEP = 8;

    /**
     * Where an item's sprite lives, tried in order. Most items are one or the other; the odd one out is a
     * block whose faces differ and so has no texture under its own name.
     */
    private static final List<String> TEXTURE_FOLDERS = List.of("item", "block");
    private static final Map<String, String> TEXTURE_EXCEPTIONS = Map.of(
            "crafting_table", "block/crafting_table_front");

    private static final CraftLog INSTANCE = new CraftLog();

    private final Deque<Craft> recent = new ArrayDeque<>();

    /** Crafts the reward has not been told about yet. Emptied by the reading, once a step. */
    private final Deque<Resource> unscored = new ArrayDeque<>();
    private final Map<String, byte[]> textures = new ConcurrentHashMap<>();

    private CraftLog() {
    }

    public static CraftLog get() {
        return INSTANCE;
    }

    /** One thing made, and when. */
    public record Craft(String item, int count, long at) {
    }

    /**
     * Records a craft. The count is one craft's output — a shift-click can chain several, and this reports
     * the recipe's yield rather than the total that chain produced.
     */
    public void record(ItemStack result) {
        if (result.isEmpty()) {
            return;
        }
        String item = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath();
        synchronized (recent) {
            recent.addFirst(new Craft(item, result.getCount(), System.currentTimeMillis()));
            while (recent.size() > KEEP) {
                recent.removeLast();
            }
        }
        Resource.of(result).ifPresent(made -> {
            synchronized (unscored) {
                unscored.addLast(made);
            }
        });
        textures.computeIfAbsent(item, this::loadTexture);
        log.debug("Crafted {} x{}", item, result.getCount());
    }

    public List<Craft> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    /** What has been made since this was last asked, and start counting again. */
    public List<Resource> drainCrafted() {
        synchronized (unscored) {
            List<Resource> made = List.copyOf(unscored);
            unscored.clear();
            return made;
        }
    }

    public void clear() {
        synchronized (unscored) {
            unscored.clear();
        }
        synchronized (recent) {
            recent.clear();
        }
    }

    /** The sprite bytes for an item, or an empty array when the pack has nothing under a name we tried. */
    public byte[] texture(String item) {
        return textures.getOrDefault(item, new byte[0]);
    }

    private byte[] loadTexture(String item) {
        String exception = TEXTURE_EXCEPTIONS.get(item);
        if (exception != null) {
            byte[] found = read("minecraft:textures/" + exception + ".png");
            if (found.length > 0) {
                return found;
            }
        }
        for (String folder : TEXTURE_FOLDERS) {
            byte[] found = read("minecraft:textures/" + folder + "/" + item + ".png");
            if (found.length > 0) {
                return found;
            }
        }
        log.debug("No texture found for {}", item);
        return new byte[0];
    }

    private byte[] read(String path) {
        try {
            // The pack's own Resource, spelled out: this file already has one of that name and it is
            // not this one.
            List<net.minecraft.server.packs.resources.Resource> stack =
                    Minecraft.getInstance().getResourceManager().getResourceStack(Identifier.parse(path));
            if (stack.isEmpty()) {
                return new byte[0];
            }
            // Last wins: that is the pack sitting on top of vanilla, so a resource pack's art is used.
            try (InputStream in = stack.get(stack.size() - 1).open()) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read {}", path, e);
            return new byte[0];
        }
    }
}
