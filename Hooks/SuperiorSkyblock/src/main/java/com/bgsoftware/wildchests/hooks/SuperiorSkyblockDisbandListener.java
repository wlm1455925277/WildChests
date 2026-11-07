package com.bgsoftware.wildchests.hooks;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.events.IslandChunkResetEvent;
import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.scheduler.Scheduler;
import com.bgsoftware.wildchests.utils.BlockPosition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Uses reset notifications as well: SSBProxyBridge can call disbandIsland without a disband event. */
public final class SuperiorSkyblockDisbandListener implements Listener {

    private final WildChestsPlugin plugin;
    private final Set<UUID> pending = new HashSet<>();

    public SuperiorSkyblockDisbandListener(WildChestsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(IslandDisbandEvent event) {
        scheduleCleanup(event.getIsland());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkReset(IslandChunkResetEvent event) {
        // A normal world reset keeps the island in the grid and must not delete all its chests.
        scheduleCleanup(event.getIsland());
    }

    private void scheduleCleanup(Island island) {
        if (!plugin.getSettings().superiorSkyblockDisbandDelete || island.isSpawn())
            return;
        // Reset callbacks can arrive asynchronously. Snapshot and eviction belong on the server scheduler.
        Scheduler.runTask(() -> {
            UUID uuid = island.getUniqueId();
            if (!pending.add(uuid))
                return;
            try {
                Set<BlockPosition> positions = findIslandChests(island);
                Scheduler.runTask(() -> {
                    pending.remove(uuid);
                    // Check the outcome, including late cancellation. Migration replaces the object but keeps its UUID.
                    if (plugin.getSettings().superiorSkyblockDisbandDelete &&
                            SuperiorSkyblockAPI.getIslandByUUID(uuid) == null && !positions.isEmpty()) {
                        plugin.getDataHandler().deleteChests(positions);
                        WildChestsPlugin.log("Disbanded island " + uuid + ": removing " + positions.size() + " chest positions.");
                    }
                }, 1L);
            } catch (RuntimeException ex) {
                pending.remove(uuid);
                WildChestsPlugin.log("&cCould not determine chest bounds for island " + uuid + "; keeping its data.");
                ex.printStackTrace();
            }
        });
    }

    Set<BlockPosition> findIslandChests(Island island) {
        Location minimum = island.getMinimumProtected();
        Location maximum = island.getMaximumProtected();
        Map<String, Boolean> worlds = new HashMap<>();
        Set<BlockPosition> positions = new HashSet<>();
        for (BlockPosition position : plugin.getDataHandler().getStoredChestPositions()) {
            if (position.getX() < minimum.getBlockX() || position.getX() > maximum.getBlockX() ||
                    position.getZ() < minimum.getBlockZ() || position.getZ() > maximum.getBlockZ())
                continue;
            if (worlds.computeIfAbsent(position.getWorldName(), name -> isIslandWorld(island, name)))
                positions.add(position);
        }
        return positions;
    }

    private boolean isIslandWorld(Island island, String name) {
        Object grid = SuperiorSkyblockAPI.getGrid();
        try {
            // Modern SSB2 lazy/slime worlds retain their identity even when Bukkit has unloaded them.
            // Resolve this optionally to retain compatibility with the old SSB2 API used by this hook.
            Method method = grid.getClass().getMethod("getIslandsWorldInfo", Island.class, String.class);
            return method.invoke(grid, island, name) != null;
        } catch (NoSuchMethodException ignored) {
            World world = Bukkit.getWorld(name);
            if (world == null)
                return false;
            Location center = island.getMinimumProtected();
            return island.isInsideRange(new Location(world, center.getBlockX(), 0, center.getBlockZ()));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not resolve island world " + name, ex);
        }
    }
}
