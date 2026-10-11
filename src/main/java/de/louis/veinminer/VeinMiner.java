package de.louis.veinminer;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

public class VeinMiner extends JavaPlugin implements Listener {

    private enum Mode { ALWAYS, SNEAK, NO_SNEAK }

    private Mode mode;
    private boolean requirePickaxe;
    private int maxBlocks;

    /** Verhindert, dass unsere eigenen BlockBreakEvents erneut eine Ader auslösen. */
    private boolean busy = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String m = getConfig().getString("mode", "always").toUpperCase().replace('-', '_');
        try {
            mode = Mode.valueOf(m);
        } catch (IllegalArgumentException e) {
            mode = Mode.ALWAYS;
        }
        requirePickaxe = getConfig().getBoolean("require-pickaxe", true);
        maxBlocks = getConfig().getInt("max-blocks", 64);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (busy) return;
        Player player = event.getPlayer();
        Block origin = event.getBlock();
        Material type = origin.getType();

        if (!isOre(type)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!player.hasPermission("veinminer.use")) return;
        if (mode == Mode.SNEAK && !player.isSneaking()) return;
        if (mode == Mode.NO_SNEAK && player.isSneaking()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (requirePickaxe && !tool.getType().name().endsWith("_PICKAXE")) return;
        if (!origin.isPreferredTool(tool)) return;

        Set<Block> vein = collectVein(origin, groupOf(type));

        busy = true;
        try {
            for (Block block : vein) {
                if (block.equals(origin)) continue; // den Ursprungsblock baut Minecraft selbst ab

                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) break; // Werkzeug ist kaputt gegangen
                if (!block.isPreferredTool(hand)) continue;

                // Echtes Break-Event, damit Schutz- und Jobs-Plugins mitspielen
                BlockBreakEvent sub = new BlockBreakEvent(block, player);
                getServer().getPluginManager().callEvent(sub);
                if (sub.isCancelled()) continue;

                if (sub.isDropItems()) {
                    block.breakNaturally(hand, true, true);
                } else {
                    block.setType(Material.AIR);
                }
                hand.damage(1, player);
            }
        } finally {
            busy = false;
        }
    }

    /** Sammelt alle zusammenhängenden Erzblöcke der gleichen Sorte (inkl. Deepslate-Variante). */
    private Set<Block> collectVein(Block start, String group) {
        Set<Block> vein = new LinkedHashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        vein.add(start);
        queue.add(start);

        while (!queue.isEmpty() && vein.size() < maxBlocks) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = current.getRelative(dx, dy, dz);
                        Material t = n.getType();
                        if (isOre(t) && groupOf(t).equals(group) && vein.add(n)) {
                            queue.add(n);
                            if (vein.size() >= maxBlocks) return vein;
                        }
                    }
                }
            }
        }
        return vein;
    }

    private boolean isOre(Material type) {
        String name = type.name();
        return name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS;
    }

    /** DEEPSLATE_IRON_ORE und IRON_ORE gehören zur gleichen Ader. */
    private String groupOf(Material type) {
        String name = type.name();
        return name.startsWith("DEEPSLATE_") ? name.substring("DEEPSLATE_".length()) : name;
    }
}
