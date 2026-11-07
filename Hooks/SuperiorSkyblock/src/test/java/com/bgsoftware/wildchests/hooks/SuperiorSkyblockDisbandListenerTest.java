package com.bgsoftware.wildchests.hooks;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.events.IslandChunkResetEvent;
import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.handlers.GridManager;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.handlers.DataHandler;
import com.bgsoftware.wildchests.handlers.SettingsHandler;
import com.bgsoftware.wildchests.scheduler.Scheduler;
import com.bgsoftware.wildchests.utils.BlockPosition;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SuperiorSkyblockDisbandListenerTest {
    public interface LazyGrid extends GridManager {
        Object getIslandsWorldInfo(Island island, String worldName);
    }

    private MockedStatic<SuperiorSkyblockAPI> api;
    private MockedStatic<Scheduler> scheduler;
    private MockedStatic<WildChestsPlugin> pluginStatics;
    private MockedStatic<Bukkit> bukkit;
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private WildChestsPlugin plugin;
    private DataHandler data;
    private Island island;
    private LazyGrid grid;
    private SuperiorSkyblockDisbandListener listener;
    private final UUID uuid = UUID.randomUUID();
    private final BlockPosition chest = new BlockPosition("island_" + uuid, 10, -64, 20);

    @BeforeEach
    void setup() throws Exception {
        pluginStatics = mockStatic(WildChestsPlugin.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        api = mockStatic(SuperiorSkyblockAPI.class);
        scheduler = mockStatic(Scheduler.class);
        scheduler.when(() -> Scheduler.runTask(any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(0)); return null;
        });
        scheduler.when(() -> Scheduler.runTask(any(Runnable.class), anyLong())).thenAnswer(call -> {
            tasks.add(call.getArgument(0)); return null;
        });
        plugin = mock(WildChestsPlugin.class);
        data = mock(DataHandler.class);
        SettingsHandler settings = mock(SettingsHandler.class);
        setEnabled(settings, true);
        when(plugin.getSettings()).thenReturn(settings);
        when(plugin.getDataHandler()).thenReturn(data);
        island = mock(Island.class);
        when(island.getUniqueId()).thenReturn(uuid);
        when(island.getMinimumProtected()).thenReturn(new Location(null, -50, 0, -50));
        when(island.getMaximumProtected()).thenReturn(new Location(null, 50, 0, 50));
        when(data.getStoredChestPositions()).thenReturn(Set.of(chest));
        grid = mock(LazyGrid.class);
        api.when(SuperiorSkyblockAPI::getGrid).thenReturn(grid);
        when(grid.getIslandsWorldInfo(island, chest.getWorldName())).thenReturn(new Object());
        listener = new SuperiorSkyblockDisbandListener(plugin);
    }

    private void setEnabled(SettingsHandler settings, boolean enabled) throws Exception {
        java.lang.reflect.Field field = SettingsHandler.class.getField("superiorSkyblockDisbandDelete");
        field.setAccessible(true);
        field.set(settings, enabled);
    }

    @AfterEach
    void close() { scheduler.close(); api.close(); pluginStatics.close(); bukkit.close(); }

    private void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }

    @Test
    void confirmedDisbandDeletesUnloadedWorldData() {
        listener.onDisband(new IslandDisbandEvent(null, island));
        drain();
        verify(data).deleteChests(Set.of(chest));
    }

    @Test
    void cancelledOrUnfinishedDisbandKeepsData() {
        api.when(() -> SuperiorSkyblockAPI.getIslandByUUID(uuid)).thenReturn(island);
        listener.onDisband(new IslandDisbandEvent(null, island));
        drain();
        verify(data, never()).deleteChests(anySet());
    }

    @Test
    void proxyDirectDisbandIsDetectedThroughChunkReset() {
        listener.onChunkReset(new IslandChunkResetEvent(island, null, 0, 0));
        drain();
        verify(data).deleteChests(Set.of(chest));
    }

    @Test
    void normalResetAndHostMigrationKeepData() {
        // A RemoteIsland replacement has the same UUID, even though it is a different object.
        api.when(() -> SuperiorSkyblockAPI.getIslandByUUID(uuid)).thenReturn(mock(Island.class));
        listener.onChunkReset(new IslandChunkResetEvent(island, null, 0, 0));
        drain();
        verify(data, never()).deleteChests(anySet());
    }

    @Test
    void duplicateChunkEventsOnlyDeleteOnce() {
        for (int i = 0; i < 10; i++)
            listener.onChunkReset(new IslandChunkResetEvent(island, null, i, 0));
        drain();
        verify(data, times(1)).deleteChests(Set.of(chest));
    }

    @Test
    void boundsAndWorldIdentityExcludeOtherIslandsAndIncludeAllHeights() {
        BlockPosition nether = new BlockPosition("island_" + uuid + "_nether", -50, 320, 50);
        when(grid.getIslandsWorldInfo(island, nether.getWorldName())).thenReturn(new Object());
        when(data.getStoredChestPositions()).thenReturn(new HashSet<>(Set.of(chest, nether,
                new BlockPosition(chest.getWorldName(), 51, 10, 20),
                new BlockPosition("another_island", 10, 10, 20))));
        assertEquals(Set.of(chest, nether), listener.findIslandChests(island));
    }

    @Test
    void spawnAndDisabledFeatureNeverDelete() throws Exception {
        setEnabled(plugin.getSettings(), false);
        listener.onDisband(new IslandDisbandEvent(null, island));
        drain();
        verify(data, never()).deleteChests(anySet());
        setEnabled(plugin.getSettings(), true);
        when(island.isSpawn()).thenReturn(true);
        listener.onDisband(new IslandDisbandEvent(null, island));
        drain();
        verify(data, never()).deleteChests(anySet());
    }
}
