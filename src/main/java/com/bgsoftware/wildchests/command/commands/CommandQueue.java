package com.bgsoftware.wildchests.command.commands;

import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.command.ICommand;
import com.bgsoftware.wildchests.handlers.DataHandler;
import com.bgsoftware.wildchests.handlers.SettingsHandler;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public final class CommandQueue implements ICommand {

    @Override
    public String getLabel() {
        return "queue";
    }

    @Override
    public String getUsage() {
        return "chests queue";
    }

    @Override
    public String getPermission() {
        return "wildchests.queue";
    }

    @Override
    public String getDescription() {
        return "Shows queue and database stats.";
    }

    @Override
    public int getMinArgs() {
        return 1;
    }

    @Override
    public int getMaxArgs() {
        return 1;
    }

    @Override
    public void perform(WildChestsPlugin plugin, CommandSender sender, String[] args) {
        DataHandler.QueueStats stats = plugin.getDataHandler().getQueueStats();
        SettingsHandler settings = plugin.getSettings();

        sender.sendMessage(ChatColor.DARK_PURPLE + "[WildChests] Queue stats");
        sender.sendMessage(ChatColor.GRAY + "saveQueue=" + stats.saveQueueSize +
                " savePositions=" + stats.saveQueuedPositionsSize +
                " loadQueue=" + stats.loadQueueSize +
                " loadPositions=" + stats.loadQueuedPositionsSize +
                " activationQueue=" + stats.activationQueueSize +
                " activationPositions=" + stats.activationQueuedPositionsSize);
        sender.sendMessage(ChatColor.GRAY + "ioTaskScheduled=" + stats.ioTaskScheduled +
                " shuttingDown=" + stats.shuttingDown +
                " pendingDb=" + stats.pendingDbTransactions);
        sender.sendMessage(ChatColor.GRAY + "batchSave=" + settings.chunkSaveBatchSize +
                " batchLoad=" + settings.chunkLoadBatchSize +
                " queueInterval=" + settings.queueIntervalTicks);
        sender.sendMessage(ChatColor.GRAY + "autoSaveTicks=" + settings.autoSaveIntervalTicks +
                " saveOnChange=" + settings.saveOnChange +
                " debug=" + settings.debugEnabled);
    }

    @Override
    public List<String> tabComplete(WildChestsPlugin plugin, CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
