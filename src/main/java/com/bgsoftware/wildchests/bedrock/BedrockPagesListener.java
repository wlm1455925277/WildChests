package com.bgsoftware.wildchests.bedrock;

import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.api.WildChestsAPI;
import com.bgsoftware.wildchests.api.objects.chests.Chest;
import com.bgsoftware.wildchests.api.objects.chests.LinkedChest;
import com.bgsoftware.wildchests.api.objects.chests.StorageChest;
import com.bgsoftware.wildchests.api.objects.data.ChestData;
import com.bgsoftware.wildchests.handlers.SettingsHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class BedrockPagesListener implements Listener {

    private final WildChestsPlugin plugin;
    private final GeyserBridge geyser;
    private final ConcurrentMap<UUID, Integer> closeWatchTasks = new ConcurrentHashMap<>();

    public BedrockPagesListener(WildChestsPlugin plugin) {
        this.plugin = plugin;
        this.geyser = new GeyserBridge(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestOpen(PlayerInteractEvent event) {
        SettingsHandler settings = plugin.getSettings();
        if (!settings.bedrockPagesEnabled) return;

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null)
            return;

        Material type = event.getClickedBlock().getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST)
            return;

        Chest chest = resolveChest(event.getClickedBlock().getLocation());
        if (chest == null) {
            return;
        }

        Player player = event.getPlayer();
        if (!geyser.isBedrockPlayer(player.getUniqueId())) {
            return;
        }

        int pages = Math.max(1, chest.getPagesAmount());
        if (settings.bedrockPagesOnlyMultiPage && pages <= 1)
            return;

        if (pages <= 0) {
            sendIfNotEmpty(player, settings.bedrockPagesMessageNoPages);
            return;
        }

        boolean opened = openPageForm(player, chest, 0);
        if (opened) {
            event.setCancelled(true);
        } else {
            sendIfNotEmpty(player, settings.bedrockPagesMessageFormUnavailable);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelCloseWatcher(event.getPlayer().getUniqueId());
    }

    private void startCloseWatcher(Player player, Chest chest) {
        UUID uuid = player.getUniqueId();
        cancelCloseWatcher(uuid);
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!player.isOnline()) {
                cancelCloseWatcher(uuid);
                return;
            }
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!(top instanceof com.bgsoftware.wildchests.objects.inventory.CraftWildInventory)) {
                plugin.getNMSAdapter().playChestAction(chest.getLocation(), false);
                cancelCloseWatcher(uuid);
                return;
            }
            Chest owner = ((com.bgsoftware.wildchests.objects.inventory.CraftWildInventory) top).getOwner();
            if (owner == null || !owner.equals(chest)) {
                plugin.getNMSAdapter().playChestAction(chest.getLocation(), false);
                cancelCloseWatcher(uuid);
            }
        }, 5L, 5L);
        closeWatchTasks.put(uuid, taskId);
    }

    private void cancelCloseWatcher(UUID uuid) {
        Integer taskId = closeWatchTasks.remove(uuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }


    private static Chest resolveChest(Location location) {
        Chest chest = WildChestsAPI.getChest(location);
        if (chest != null)
            return chest;
        LinkedChest linked = WildChestsAPI.getLinkedChest(location);
        if (linked != null)
            return linked;
        StorageChest storage = WildChestsAPI.getStorageChest(location);
        return storage;
    }

    private boolean openPageForm(Player player, Chest chest, int groupIndex) {
        SettingsHandler settings = plugin.getSettings();
        if (!geyser.isAvailable())
            return false;

        int pages = Math.max(1, chest.getPagesAmount());
        int perForm = Math.max(5, settings.bedrockPagesButtonsPerForm);
        int maxGroup = (pages - 1) / perForm;
        int gi = Math.max(0, Math.min(groupIndex, maxGroup));
        int startPage = gi * perForm;
        int endPage = Math.min(pages - 1, startPage + perForm - 1);

        String title = applyPlaceholders(settings.bedrockPagesFormTitle, pages, startPage, endPage);
        String content = applyPlaceholders(settings.bedrockPagesFormContent, pages, startPage, endPage);

        List<Runnable> actions = new ArrayList<>();
        GeyserBridge.FormBuilder builder = geyser.newFormBuilder();
        if (builder == null)
            return false;

        builder.title(title);
        builder.content(content);

        if (gi > 0) {
            builder.button(settings.bedrockPagesPrevLabel);
            actions.add(() -> openPageForm(player, chest, gi - 1));
        }

        Location location = chest.getLocation().clone();

        ChestData chestData = chest.getData();
        for (int page = startPage; page <= endPage; page++) {
            String pageTitle = chestData.getTitle(page + 1);
            String label = settings.bedrockPagesButtonFormat
                    .replace("{page}", String.valueOf(page + 1))
                    .replace("{pages}", String.valueOf(pages))
                    .replace("{title}", pageTitle == null ? "" : pageTitle);

            final int targetPage = page;
            builder.button(label);
            actions.add(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                Chest fresh = WildChestsAPI.getChest(location);
                if (fresh == null)
                    return;
                fresh.openPage(player, targetPage);
                startCloseWatcher(player, fresh);
            }));
        }

        if (gi < maxGroup) {
            builder.button(settings.bedrockPagesNextLabel);
            actions.add(() -> openPageForm(player, chest, gi + 1));
        }

        builder.onValid(response -> {
            int clicked = response.getClickedId();
            if (clicked < 0 || clicked >= actions.size())
                return;
            actions.get(clicked).run();
        });

        return builder.send(player.getUniqueId(), player);
    }

    private static void sendIfNotEmpty(Player player, String message) {
        if (message == null || message.isEmpty())
            return;
        player.sendMessage(message);
    }

    private static String applyPlaceholders(String text, int pages, int startPage, int endPage) {
        if (text == null)
            return "";
        return text.replace("\\n", "\n")
                .replace("{pages}", String.valueOf(pages))
                .replace("{page_from}", String.valueOf(startPage + 1))
                .replace("{page_to}", String.valueOf(endPage + 1));
    }

    private static final class GeyserBridge {
        private final WildChestsPlugin plugin;
        private final boolean available;
        private final java.lang.reflect.Method floodgateGetInstance;
        private final java.lang.reflect.Method floodgateIsPlayer;
        private final java.lang.reflect.Method[] floodgateSendFormMethods;
        private final java.lang.reflect.Method simpleFormBuilder;
        private final java.lang.reflect.Method builderTitle;
        private final java.lang.reflect.Method builderContent;
        private final java.lang.reflect.Method builderButton;
        private final java.lang.reflect.Method builderValidResultHandler;
        private final java.lang.reflect.Method builderBuild;

        private GeyserBridge(WildChestsPlugin plugin) {
            this.plugin = plugin;

            java.lang.reflect.Method getInstance = null;
            java.lang.reflect.Method isPlayer = null;
            java.lang.reflect.Method[] sendForms = null;
            java.lang.reflect.Method formBuilder = null;
            java.lang.reflect.Method title = null;
            java.lang.reflect.Method content = null;
            java.lang.reflect.Method button = null;
            java.lang.reflect.Method validHandler = null;
            java.lang.reflect.Method build = null;
            boolean ok = false;

            try {
                Class<?> floodgateApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                getInstance = floodgateApi.getMethod("getInstance");
                isPlayer = floodgateApi.getMethod("isFloodgatePlayer", UUID.class);
                sendForms = java.util.Arrays.stream(floodgateApi.getMethods())
                        .filter(method -> method.getName().equals("sendForm") && method.getParameterCount() == 2)
                        .toArray(java.lang.reflect.Method[]::new);

                Class<?> simpleForm = Class.forName("org.geysermc.cumulus.form.SimpleForm");
                formBuilder = simpleForm.getMethod("builder");
                Class<?> simpleBuilderClass = formBuilder.getReturnType();
                title = simpleBuilderClass.getMethod("title", String.class);
                content = simpleBuilderClass.getMethod("content", String.class);
                button = simpleBuilderClass.getMethod("button", String.class);
                validHandler = simpleBuilderClass.getMethod("validResultHandler", Consumer.class);
                build = simpleBuilderClass.getMethod("build");

                ok = getInstance != null && isPlayer != null && sendForms != null && sendForms.length > 0 && formBuilder != null &&
                        title != null && content != null && button != null && validHandler != null && build != null;
            } catch (Exception ex) {
                ok = false;
            }

            this.floodgateGetInstance = getInstance;
            this.floodgateIsPlayer = isPlayer;
            this.floodgateSendFormMethods = sendForms == null ? new java.lang.reflect.Method[0] : sendForms;
            this.simpleFormBuilder = formBuilder;
            this.builderTitle = title;
            this.builderContent = content;
            this.builderButton = button;
            this.builderValidResultHandler = validHandler;
            this.builderBuild = build;
            this.available = ok;

            if (!available) {
                plugin.getLogger().warning("Geyser/Floodgate form API not available. Bedrock page selector disabled.");
            }
        }

        private boolean isAvailable() {
            return available;
        }

        private boolean isBedrockPlayer(UUID uuid) {
            if (!available)
                return false;
            try {
                Object api = floodgateGetInstance.invoke(null);
                return (boolean) floodgateIsPlayer.invoke(api, uuid);
            } catch (Exception ex) {
                return false;
            }
        }

        private FormBuilder newFormBuilder() {
            if (!available)
                return null;
            try {
                Object builder = simpleFormBuilder.invoke(null);
                return new FormBuilder(builder);
            } catch (Exception ex) {
                return null;
            }
        }

        private final class FormBuilder {
            private final Object builder;

            private FormBuilder(Object builder) {
                this.builder = builder;
            }

            private void title(String title) {
                try {
                    builderTitle.invoke(builder, title);
                } catch (Exception ignored) {
                }
            }

            private void content(String content) {
                try {
                    builderContent.invoke(builder, content);
                } catch (Exception ignored) {
                }
            }

            private void button(String label) {
                try {
                    builderButton.invoke(builder, label);
                } catch (Exception ignored) {
                }
            }

            private void onValid(Consumer<FormResponse> handler) {
                try {
                    builderValidResultHandler.invoke(builder, (Consumer<Object>) response ->
                            handler.accept(new FormResponse(response)));
                } catch (Exception ignored) {
                }
            }

            private boolean send(UUID playerUuid, Player player) {
                try {
                    Object form = builderBuild.invoke(builder);
                    Object api = floodgateGetInstance.invoke(null);
                    java.lang.reflect.Method method = findSendMethod(form, playerUuid, player);
                    if (method == null) {
                        return false;
                    }
                    Object firstArg = method.getParameterTypes()[0].isInstance(player) ? player : playerUuid;
                    method.invoke(api, firstArg, form);
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        }

        private static final class FormResponse {
            private final Object response;

            private FormResponse(Object response) {
                this.response = response;
            }

            private int getClickedId() {
                if (response == null)
                    return -1;
                try {
                    java.lang.reflect.Method method = response.getClass().getMethod("clickedButtonId");
                    Object result = method.invoke(response);
                    return ((Number) result).intValue();
                } catch (Exception ignored) {
                }
                try {
                    java.lang.reflect.Method method = response.getClass().getMethod("getClickedButtonId");
                    Object result = method.invoke(response);
                    return ((Number) result).intValue();
                } catch (Exception ignored) {
                }
                return -1;
            }
        }

        private java.lang.reflect.Method findSendMethod(Object form, UUID playerUuid, Player player) {
            if (form == null)
                return null;
            for (java.lang.reflect.Method method : floodgateSendFormMethods) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2)
                    continue;
                if (!params[1].isInstance(form))
                    continue;
                Class<?> first = params[0];
                if (player != null && first.isInstance(player))
                    return method;
                if (playerUuid != null && first.isAssignableFrom(UUID.class))
                    return method;
                if (playerUuid != null && first.isInstance(playerUuid))
                    return method;
            }
            return null;
        }
    }
}
