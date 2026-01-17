package com.bgsoftware.wildchests.listeners;

import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.api.objects.chests.Chest;
import com.bgsoftware.wildchests.objects.chests.WChest;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public final class ChunksListener implements Listener {

    private final WildChestsPlugin plugin;

    public ChunksListener(WildChestsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent e) {
        handleChunkLoad(plugin, e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent e) {
        plugin.getDataHandler().enqueueChunkSave(e.getChunk());
    }

    public static void handleChunkLoad(WildChestsPlugin plugin, Chunk chunk) {
        plugin.getDataHandler().enqueueChunkLoad(chunk);
    }

    private static void loadChestsForChunk(WildChestsPlugin plugin, Chunk chunk) {
        if (!chunk.isLoaded())
            return;

        plugin.getChestsManager().getChests(chunk).forEach(chest -> {
            handleLoadedChest(plugin, chest);
        });
    }

    public static void handleLoadedChest(WildChestsPlugin plugin, Chest chest) {
        Location location = chest.getLocation();
        Material blockType = location.getBlock().getType();
        if (blockType != Material.CHEST) {
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            WildChestsPlugin.log("Loading chunk " + chunkX + ", " + chunkZ + " but found a chest not " +
                    "associated with a chest block but " + blockType + " at " + location.getWorld().getName() + ", " +
                    location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
            chest.remove();
        } else {
            ((WChest) chest).onChunkLoad();
        }
    }

}
