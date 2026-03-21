package com.ihanuat.mod.modules.profitTracker;

import com.ihanuat.mod.MacroConfig;
import com.ihanuat.mod.MacroState;
import com.ihanuat.mod.MacroStateManager;
import com.ihanuat.mod.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks crop yields via the Cultivating enchantment counter on the held tool,
 * detects crop type from inventory changes, and monitors purse balance.
 */
public class InventoryTracker {

    private static final Map<String, Long> prevInventoryCounts = new LinkedHashMap<>();
    //60 ticks confirmation window.
    private static final Map<String, Long> accumulatedDeltas = new LinkedHashMap<>();
    private static final Set<String> MUSHROOM_NAMES = Set.of("Red Mushroom", "Brown Mushroom");
    private static final int CONFIRMATION_TICKS = 60;

    private static long lastCultivatingValue = -1;
    private static String currentFarmedCrop = "Wheat";
    private static long lastPurseBalance = -1;
    private static int confirmationTickCounter = 0;
    private static boolean cropConfirmed = false;

    /**
     * Called every tick from ProfitManager.update().
     * Scans inventory for crop changes, reads cultivating counter, and tracks purse.
     */
    public static void update(Minecraft client) {
        if (client.player == null)
            return;

        // 1. Detect crop type from the held hoe
        ItemStack held = client.player.getMainHandItem();
        if (held != null && !held.isEmpty()) {
            String heldName = ClientUtils.stripColor(held.getHoverName().getString()).trim();
            boolean isGenericTool = ItemConstants.GENERIC_TOOLS.stream().anyMatch(heldName::contains);

            if (isGenericTool) {
                // Accumulate inventory deltas over a 60 ticks window
                Map<String, Long> currentCounts = new LinkedHashMap<>();
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = client.player.getInventory().getItem(i);
                    String name = ClientUtils.stripColor(stack.getHoverName().getString()).trim();
                    if (ItemConstants.BASE_CROPS.contains(name)) {
                        currentCounts.put(name, currentCounts.getOrDefault(name, 0L) + stack.getCount());
                    }
                }

                // On first tick, just snapshot; on subsequent ticks, accumulate deltas
                if (!prevInventoryCounts.isEmpty()) {
                    for (Map.Entry<String, Long> entry : currentCounts.entrySet()) {
                        String name = entry.getKey();
                        long count = entry.getValue();
                        long prev = prevInventoryCounts.getOrDefault(name, 0L);
                        if (count > prev) {
                            accumulatedDeltas.merge(name, count - prev, Long::sum);
                        }
                    }
                }
                prevInventoryCounts.clear();
                prevInventoryCounts.putAll(currentCounts);

                confirmationTickCounter++;

                // After 3 seconds, analyze accumulated deltas and confirm the crop
                if (confirmationTickCounter >= CONFIRMATION_TICKS && !cropConfirmed) {
                    if (!accumulatedDeltas.isEmpty()) {
                        // Separate mushroom vs non-mushroom gains
                        String bestNonMushroom = null;
                        long bestNonMushroomDelta = 0;
                        String bestMushroom = null;
                        long bestMushroomDelta = 0;

                        for (Map.Entry<String, Long> entry : accumulatedDeltas.entrySet()) {
                            if (MUSHROOM_NAMES.contains(entry.getKey())) {
                                if (entry.getValue() > bestMushroomDelta) {
                                    bestMushroomDelta = entry.getValue();
                                    bestMushroom = entry.getKey();
                                }
                            } else {
                                if (entry.getValue() > bestNonMushroomDelta) {
                                    bestNonMushroomDelta = entry.getValue();
                                    bestNonMushroom = entry.getKey();
                                }
                            }
                        }

                        // If non-mushroom crop detected, pick that (mushroom is from Mooshroom Cow)
                        // If only mushroom, pick mushroom
                        String confirmed = (bestNonMushroom != null) ? bestNonMushroom : bestMushroom;
                        if (confirmed != null) {
                            if (MacroConfig.showDebug) {
                                ClientUtils.sendDebugMessage(client,
                                        "Crop confirmed (3s scan): " + confirmed + " | deltas: " + accumulatedDeltas);
                            }
                            currentFarmedCrop = confirmed;
                            cropConfirmed = true;
                        }
                    } else if (MacroConfig.showDebug) {
                        ClientUtils.sendDebugMessage(client, "No crop deltas after 3s, retrying...");
                    }
                    // Reset for next window
                    accumulatedDeltas.clear();
                    confirmationTickCounter = 0;
                }
            } else {
                // Specific hoe: look up crop from hoe name
                for (Map.Entry<String, String> entry : ItemConstants.HOE_CROP_MAP.entrySet()) {
                    if (heldName.contains(entry.getKey())) {
                        String crop = entry.getValue();
                        if (ItemConstants.ECLIPSE_HOE_CROP.equals(crop) && client.level != null) {
                            // Day (0-12000) = Sunflower, Night (12000-24000) = Moonflower
                            long timeOfDay = client.level.getDayTime() % 24000L;
                            crop = (timeOfDay < 12000L) ? "Sunflower" : "Moonflower";
                        }
                        if (!crop.equals(currentFarmedCrop) && MacroConfig.showDebug) {
                            ClientUtils.sendDebugMessage(client, "Crop detected: " + crop + " (hoe: " + heldName + ")");
                        }
                        currentFarmedCrop = crop;
                        cropConfirmed = true;
                        break;
                    }
                }
            }
        }

        // 2. Track Cultivating counter on held item
        if (held != null && !held.isEmpty()) {
            long newValue = -1;

            // Hypixel 1.21 stores this in custom_data
            CustomData custom = held.get(DataComponents.CUSTOM_DATA);
            if (custom != null) {
                CompoundTag tag = custom.copyTag();
                if (tag.contains("farmed_cultivating")) {
                    newValue = tag.getLong("farmed_cultivating").get();
                }
            }

            if (newValue != -1) {
                if (lastCultivatingValue != -1 && newValue > lastCultivatingValue) {
                    long delta = newValue - lastCultivatingValue;
                    if (delta <= ItemConstants.MAX_CULTIVATING_DELTA && currentFarmedCrop != null) {
                        if (MacroConfig.showDebug) {
                            ClientUtils.sendDebugMessage(client, "Cultivating +" + delta + " -> " + currentFarmedCrop);
                        }
                        if (currentFarmedCrop.equalsIgnoreCase("Wheat")
                                || currentFarmedCrop.equalsIgnoreCase("Seeds")) {
                            // Ratio 1 Wheat : 1.5 Seeds (Total 2.5)
                            long wheatDelta = Math.round(delta / 2.5);
                            long seedsDelta = delta - wheatDelta;
                            if (wheatDelta > 0)
                                ProfitManager.addDrop("Wheat", wheatDelta);
                            if (seedsDelta > 0)
                                ProfitManager.addDrop("Seeds", seedsDelta);
                        } else {
                            ProfitManager.addDrop(currentFarmedCrop, delta);
                        }
                    } else if (delta > ItemConstants.MAX_CULTIVATING_DELTA && MacroConfig.showDebug) {
                        ClientUtils.sendDebugMessage(client, "Dismissed large cultivating delta: +" + delta);
                    }
                }
                lastCultivatingValue = newValue;
            } else {
                lastCultivatingValue = -1;
            }
        } else {
            lastCultivatingValue = -1;
        }

        // 3. Track Purse
        long currentPurse = ClientUtils.getPurse(client);
        if (currentPurse != -1) {
            if (lastPurseBalance != -1) {
                if (currentPurse > lastPurseBalance) {
                    if (MacroStateManager.getCurrentState() != MacroState.State.OFF &&
                            MacroStateManager.getCurrentState() != MacroState.State.AUTOSELLING) {
                        long delta = currentPurse - lastPurseBalance;
                        if (delta <= 50000) {
                            if (MacroConfig.showDebug) {
                                ClientUtils.sendDebugMessage(client, "Purse +" + delta);
                            }
                            ProfitManager.addDrop("Purse", delta);
                        } else if (MacroConfig.showDebug) {
                            ClientUtils.sendDebugMessage(client, "Dismissed large purse change: +" + delta);
                        }
                    }
                }
            }
            lastPurseBalance = currentPurse;
        }
    }

    /**
     * Resets tracking state (called on macro start/restart).
     */
    public static void reset() {
        lastPurseBalance = -1;
        prevInventoryCounts.clear();
        accumulatedDeltas.clear();
        confirmationTickCounter = 0;
        cropConfirmed = false;
    }
}

