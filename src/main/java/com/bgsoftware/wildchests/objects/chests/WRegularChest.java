package com.bgsoftware.wildchests.objects.chests;

import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.api.objects.chests.RegularChest;
import com.bgsoftware.wildchests.api.objects.data.ChestData;
import com.bgsoftware.wildchests.api.objects.data.InventoryData;
import com.bgsoftware.wildchests.handlers.ChestsHandler;
import com.bgsoftware.wildchests.objects.inventory.CraftWildInventory;
import com.bgsoftware.wildchests.objects.inventory.InventoryHolder;
import com.bgsoftware.wildchests.objects.inventory.WildContainerItem;
import com.bgsoftware.wildchests.utils.ItemUtils;
import com.bgsoftware.wildchests.utils.SyncedArray;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.UUID;

public class WRegularChest extends WChest implements RegularChest {

    protected SyncedArray<CraftWildInventory> inventories;
    private int[] pageEnds;
    private SyncedArray<CraftWildInventory> layoutInventories;
    private long layoutRevision;
    private List<WildContainerItem>[] pageContents;
    private final List<WildContainerItem> contentsView = new ContentsView();

    public WRegularChest(UUID placer, Location location, ChestData chestData) {
        super(placer, location, chestData);
        initContainer(chestData);
    }

    @Override
    public Inventory getPage(int page) {
        return page < 0 || page >= getPagesAmount() ? null : inventories.get(page);
    }

    @Override
    public Inventory[] getPages() {
        return inventories.stream().toArray(Inventory[]::new);
    }

    @Override
    public Inventory setPage(int page, int size, String title) {
        ChestData chestData = getData();
        checkCapacity(page + 1, chestData.getDefaultSize(), chestData.getDefaultTitle());
        CraftWildInventory inventory = plugin.getNMSInventory().createInventory(this, size, title, page);
        inventories.set(page, inventory);
        rebuildPageLayout();
        updateTitles();
        markDirty();
        return inventory;
    }

    @Override
    public int getPagesAmount() {
        return inventories.length();
    }

    @Override
    public int getPageIndex(Inventory inventory) {
        for (int i = 0; i < getPagesAmount(); i++) {
            if (inventories.get(i).equals(inventory))
                return i;
        }

        return -1;
    }

    @Override
    public List<WildContainerItem> getWildContents() {
        List<WildContainerItem> contents = new ArrayList<>();

        int pagesAmount = getPagesAmount();

        for (int page = 0; page < pagesAmount; page++) {
            CraftWildInventory inventory = inventories.get(page);
            contents.addAll(inventory.getWildContents());
        }

        return contents;
    }

    // NMS container checks only need a view, not a copy of every inventory page.
    // Read the current pages on access so replacing or adding a page stays visible.
    public List<WildContainerItem> getWildContentsView() {
        return contentsView;
    }

    @SuppressWarnings("unchecked")
    private void rebuildPageLayout() {
        int[] ends = new int[getPagesAmount()];
        List<WildContainerItem>[] contents = new List[getPagesAmount()];
        int total = 0;
        for (int page = 0; page < ends.length; page++) {
            contents[page] = inventories.get(page).getWildContents();
            total += contents[page].size();
            ends[page] = total;
        }
        pageContents = contents;
        pageEnds = ends;
        layoutInventories = inventories;
        layoutRevision = inventories.getRevision();
    }

    private void ensurePageLayout() {
        // Linked chests can replace the shared array or resize it through another chest.
        if (layoutInventories != inventories || layoutRevision != inventories.getRevision())
            rebuildPageLayout();
    }

    private int findPage(int slot) {
        if (slot < 0 || slot >= contentsView.size()) return -1;
        int page = Arrays.binarySearch(pageEnds, slot + 1);
        return page < 0 ? -page - 1 : page;
    }

    private final class ContentsView extends AbstractList<WildContainerItem> implements RandomAccess {
        @Override
        public WildContainerItem get(int index) {
            int page = findPage(index);
            if (page < 0) throw new IndexOutOfBoundsException("index: " + index);
            return pageContents[page].get(index - (page == 0 ? 0 : pageEnds[page - 1]));
        }

        @Override
        public int size() {
            ensurePageLayout();
            return pageEnds == null || pageEnds.length == 0 ? 0 : pageEnds[pageEnds.length - 1];
        }
    }

    @Override
    public WildContainerItem getWildItem(int slot) {
        int page = findPage(slot);
        return page < 0 ? WildContainerItem.AIR.copy() :
                pageContents[page].get(slot - (page == 0 ? 0 : pageEnds[page - 1]));
    }

    @Override
    public void setItem(int slot, WildContainerItem itemStack) {
        int page = findPage(slot);
        if (page < 0) return;
        int localSlot = slot - (page == 0 ? 0 : pageEnds[page - 1]);
        CraftWildInventory inventory = inventories.get(page);
        if (areItemsEqual(inventory.getWildItem(localSlot), itemStack)) return;
        inventory.setItem(localSlot, itemStack);
        markDirty();
    }

    @Override
    public void loadFromData(ChestsHandler.UnloadedChest unloadedChest) {
        boolean previous = beginSaveSuppression();
        try {
            if (!(unloadedChest instanceof ChestsHandler.UnloadedRegularChest)) {
                WildChestsPlugin.log("&cCannot load data to chest " + getLocation() + " from " + unloadedChest);
                return;
            }

            ChestsHandler.UnloadedRegularChest unloadedRegularChest =
                    (ChestsHandler.UnloadedRegularChest) unloadedChest;

            InventoryHolder[] inventories = unloadedRegularChest.getInventories();
            for (int i = 0; i < inventories.length; i++)
                setPage(i, inventories[i]);

            if (unloadedRegularChest.executeUpdate)
                plugin.getDataHandler().saveChestInventory(this);
        } finally {
            endSaveSuppression(previous);
        }
    }

    private void checkCapacity(int size, int inventorySize, String inventoryTitle) {
        int oldSize = getPagesAmount();
        if (size > oldSize) {
            inventories.increaseCapacity(size);
            for (int i = oldSize; i < size; i++)
                inventories.set(i, plugin.getNMSInventory().createInventory(this, inventorySize, inventoryTitle, i));
        }
    }

    protected void initContainer(ChestData chestData) {
        this.inventories = new SyncedArray<>(chestData.getDefaultPagesAmount());

        int size = chestData.getDefaultSize();
        Map<Integer, InventoryData> pagesData = chestData.getPagesData();

        for (int i = 0; i < getPagesAmount(); i++) {
            String title = pagesData.containsKey(i + 1) ? pagesData.get(i + 1).getTitle() : chestData.getDefaultTitle();
            inventories.set(i, plugin.getNMSInventory().createInventory(this, size, title, i));
        }
        rebuildPageLayout();
    }

    private void updateTitles() {
        for (int i = 0; i < inventories.length(); i++) {
            inventories.get(i).setTitle(getData().getTitle(i + 1).replace("{0}", getPagesAmount() + ""));
        }
    }

    private boolean areItemsEqual(WildContainerItem currentItem, WildContainerItem newItem) {
        ItemStack currentStack = currentItem == null ? null : currentItem.getBukkitItem();
        ItemStack newStack = newItem == null ? null : newItem.getBukkitItem();

        if (ItemUtils.isEmpty(currentStack) && ItemUtils.isEmpty(newStack))
            return true;

        if (ItemUtils.isEmpty(currentStack) || ItemUtils.isEmpty(newStack))
            return false;

        return currentStack.isSimilar(newStack) && currentStack.getAmount() == newStack.getAmount();
    }

}
