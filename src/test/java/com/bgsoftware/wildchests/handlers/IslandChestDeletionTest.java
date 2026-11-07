package com.bgsoftware.wildchests.handlers;

import com.bgsoftware.common.databasebridge.sql.transaction.DeleteSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.database.DBSession;
import com.bgsoftware.wildchests.objects.chests.WChest;
import com.bgsoftware.wildchests.scheduler.Scheduler;
import com.bgsoftware.wildchests.utils.BlockPosition;
import com.bgsoftware.wildchests.utils.ChunkPosition;
import com.bgsoftware.wildchests.api.objects.chests.Chest;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IslandChestDeletionTest {
    private MockedStatic<WildChestsPlugin> pluginStatics;
    private MockedStatic<Scheduler> scheduler;
    private MockedStatic<DBSession> database;
    private DataHandler handler;
    private ChestsHandler chests;
    private final BlockPosition removed = new BlockPosition("unloaded_world", 1, 64, 2);
    private final BlockPosition kept = new BlockPosition("other_world", 1, 64, 2);
    private final List<IDatabaseTransaction> submitted = new ArrayList<>();

    @BeforeEach
    void setup() {
        pluginStatics = mockStatic(WildChestsPlugin.class);
        scheduler = mockStatic(Scheduler.class);
        database = mockStatic(DBSession.class);
        WildChestsPlugin plugin = mock(WildChestsPlugin.class);
        pluginStatics.when(WildChestsPlugin::getPlugin).thenReturn(plugin);
        when(plugin.getSettings()).thenReturn(mock(SettingsHandler.class));
        chests = mock(ChestsHandler.class);
        when(plugin.getChestsManager()).thenReturn(chests);
        database.when(() -> DBSession.execute(anyCollection())).thenAnswer(call -> {
            submitted.addAll(call.<Collection<IDatabaseTransaction>>getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        handler = new DataHandler(plugin);
    }

    @AfterEach
    void close() { database.close(); scheduler.close(); pluginStatics.close(); }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) throws Exception {
        Field field = DataHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(handler);
    }

    @Test
    void deletesAllChestTypesAndPurgesEveryQueueWithoutTouchingOtherWorlds() throws Exception {
        fieldSet("storedPositions").addAll(Set.of(removed, kept));
        WChest deletedChest = mock(WChest.class);
        WChest keptChest = mock(WChest.class);
        when(deletedChest.getBlockPosition()).thenReturn(removed);
        when(keptChest.getBlockPosition()).thenReturn(kept);
        for (String queue : List.of("saveQueue", "activationQueue")) {
            ArrayDeque<WChest> values = field(queue);
            values.add(deletedChest);
            values.add(keptChest);
        }
        for (String set : List.of("saveQueuedPositions", "loadQueuedPositions", "activationQueuedPositions"))
            fieldSet(set).addAll(Set.of(removed, kept));
        ChestsHandler.UnloadedChest unloaded = mock(ChestsHandler.UnloadedChest.class);
        Field position = ChestsHandler.UnloadedChest.class.getField("position");
        position.setAccessible(true);
        position.set(unloaded, removed);
        this.<ArrayDeque<ChestsHandler.UnloadedChest>>field("loadQueue").add(unloaded);

        handler.deleteChests(Set.of(removed));

        assertEquals(Set.of(kept), handler.getStoredChestPositions());
        for (String queue : List.of("saveQueue", "activationQueue"))
            assertEquals(List.of(keptChest), new ArrayList<>(this.<ArrayDeque<WChest>>field(queue)));
        assertTrue(this.<ArrayDeque<?>>field("loadQueue").isEmpty());
        for (String set : List.of("saveQueuedPositions", "loadQueuedPositions", "activationQueuedPositions"))
            assertEquals(Set.of(kept), fieldSet(set));
        verify(chests).forgetChests(Set.of(removed));
        assertEquals(3, submitted.size());
        Set<String> tables = Set.of("chests", "linked_chests", "storage_units");
        for (IDatabaseTransaction transaction : submitted) {
            DeleteSQLDatabaseTransaction deletion = (DeleteSQLDatabaseTransaction) transaction;
            assertTrue(tables.stream().anyMatch(table -> deletion.buildQuery().contains(table)));
            assertTrue(deletion.buildQuery().contains("location"));
            assertEquals(1, deletion.getValues().size());
        }
    }

    private Set<BlockPosition> fieldSet(String name) throws Exception { return field(name); }

    @Test
    void missingChestDefinitionStillIndexesItsDatabaseRowForDeletion() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.next()).thenReturn(true, false);
        when(row.getString("location")).thenReturn("unloaded_world, 1, 64, 2");
        when(row.getString("placer")).thenReturn(UUID.randomUUID().toString());
        when(row.getString("chest_data")).thenReturn("removed-definition");
        Method load = DataHandler.class.getDeclaredMethod("loadResultSet", ResultSet.class, String.class, List.class);
        load.setAccessible(true);
        load.invoke(handler, row, "chests", new ArrayList<>());
        assertEquals(Set.of(removed), handler.getStoredChestPositions());
    }

    @Test
    void removedChestCannotBeWrittenByAnOldSaveSnapshot() {
        WChest chest = mock(WChest.class);
        when(chest.isRemoved()).thenReturn(true);
        handler.saveDatabase(List.of(chest));
        assertTrue(submitted.isEmpty());
        database.verifyNoInteractions();
    }

    @Test
    void emptyDeletionDoesNotSubmitSql() {
        handler.deleteChests(Set.of());
        database.verifyNoInteractions();
        verifyNoInteractions(chests);
    }

    @SuppressWarnings("unchecked")
    private <T> T managerField(ChestsHandler manager, String name) throws Exception {
        Field field = ChestsHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(manager);
    }

    @Test
    void evictsLoadedAndUnloadedCachesWithoutResolvingOrLoadingTheirWorld() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ChestsHandler manager = new ChestsHandler();
            WChest deletedChest = mock(WChest.class);
            WChest keptChest = mock(WChest.class);
            Map<BlockPosition, Chest> loaded = managerField(manager, "chests");
            loaded.put(removed, deletedChest);
            loaded.put(kept, keptChest);
            Map<ChunkPosition, Set<Chest>> chunks = managerField(manager, "chestsByChunks");
            chunks.put(ChunkPosition.of(removed), new HashSet<>(Set.of(deletedChest)));
            Map<String, Set<Chest>> worlds = managerField(manager, "chestsByWorlds");
            worlds.put(removed.getWorldName(), new HashSet<>(Set.of(deletedChest)));
            Map<ChunkPosition, Map<BlockPosition, ChestsHandler.UnloadedChest>> unloaded = managerField(manager, "unloadedChests");
            Map<BlockPosition, ChestsHandler.UnloadedChest> pending = new HashMap<>();
            pending.put(removed, mock(ChestsHandler.UnloadedChest.class));
            unloaded.put(ChunkPosition.of(removed), pending);

            manager.forgetChests(Set.of(removed));

            assertEquals(Map.of(kept, keptChest), loaded);
            assertTrue(unloaded.isEmpty());
            assertTrue(chunks.get(ChunkPosition.of(removed)).isEmpty());
            assertTrue(worlds.get(removed.getWorldName()).isEmpty());
            verify(deletedChest).markAsRemoved();
            verifyNoInteractions(keptChest);
            verify(deletedChest, never()).getLocation();
            verify(deletedChest, never()).getPages();
        }
    }
}
