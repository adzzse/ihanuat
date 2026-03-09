package com.ihanuat.mod.modules;

import com.ihanuat.mod.MacroConfig;
import com.ihanuat.mod.MacroState;
import com.ihanuat.mod.MacroStateManager;
import com.ihanuat.mod.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class PestManager {
    // Shared state
    public static volatile boolean isCleaningInProgress = false;
    public static volatile String currentInfestedPlot = null;
    public static volatile int currentPestSessionId = 0;
    private static long lastZeroPestTime = 0;

    private static boolean isThresholdMet(int aliveCount) {
        return aliveCount >= MacroConfig.pestThreshold || aliveCount >= 8;
    }

    public static void reset() {
        isCleaningInProgress = false;
        currentInfestedPlot = null;
        lastZeroPestTime = 0;
        currentPestSessionId++;
        
        PestPrepSwapManager.resetState();
        PestReturnManager.resetState();
        PestAotvManager.resetState();
        PestBonusManager.resetState();
    }

    public static void checkTabListForPests(Minecraft client, MacroState.State currentState) {
        if (client.getConnection() == null || client.player == null || !MacroStateManager.isMacroRunning())
            return;

        if (isCleaningInProgress && currentState == MacroState.State.FARMING) {
            isCleaningInProgress = false;
        }

        PestTabListParser.TabListData data = PestTabListParser.parseTabList(client);
        
        // Update bonus status
        PestBonusManager.isBonusInactive = data.bonusFound;

        // Handle prep swap flag updates based on cooldown
        if (data.cooldownSeconds != -1) {
            PestPrepSwapManager.updatePrepSwapFlag(data.cooldownSeconds, isCleaningInProgress);

            // Check if prep swap should be triggered
            boolean thresholdMet = isThresholdMet(data.aliveCount);
            if (!thresholdMet && PestPrepSwapManager.shouldTriggerPrepSwap(
                    currentState, data.cooldownSeconds, isCleaningInProgress, PestReturnManager.isReturnToLocationActive)) {
                PestPrepSwapManager.triggerPrepSwap(client);
            }
        }

        // Failsafe: if CLEANING and 0 pests for 10s, return to farming.
        // Do not apply this during SPRAYING because spray routes can legitimately
        // travel multiple plots with 0 alive pests between spray actions.
        if (currentState == MacroState.State.CLEANING) {
            if (data.aliveCount <= 0) {
                if (lastZeroPestTime == 0) {
                    lastZeroPestTime = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastZeroPestTime > 10000) {
                    client.player.displayClientMessage(
                            Component.literal("§cFail-safe: No pests detected for 10s. Returning to farm."), true);
                    lastZeroPestTime = 0;
                    handlePestCleaningFinished(client);
                    return;
                }
            } else {
                lastZeroPestTime = 0;
            }
        } else {
            lastZeroPestTime = 0;
        }

        if (isCleaningInProgress)
            return;

        // Check if cleaning should be triggered
        if (isThresholdMet(data.aliveCount)) {
            if (data.aliveCount >= 8 && data.aliveCount < 99) {
                client.player.displayClientMessage(Component.literal("§eMax Pests (8) reached. Starting cleaning..."),
                        true);
            }
            String targetPlot = data.infestedPlots.isEmpty() ? "0" : data.infestedPlots.iterator().next();
            startCleaningSequence(client, targetPlot);
        }
    }

    public static boolean tryStartCleaningSequenceFromChat(Minecraft client, String requestedPlot) {
        if (client == null || client.getConnection() == null || client.player == null || isCleaningInProgress) {
            return false;
        }

        PestTabListParser.TabListData data = PestTabListParser.parseTabList(client);
        if (!isThresholdMet(data.aliveCount)) {
            if (MacroConfig.showDebug) {
                ClientUtils.sendDebugMessage(client,
                        "Chat pest trigger ignored: alive=" + data.aliveCount + " < threshold=" + MacroConfig.pestThreshold);
            }
            return false;
        }

        String targetPlot = requestedPlot;
        if ((targetPlot == null || targetPlot.isBlank() || "0".equals(targetPlot)) && !data.infestedPlots.isEmpty()) {
            targetPlot = data.infestedPlots.iterator().next();
        }

        startCleaningSequence(client, targetPlot);
        return true;
    }

    public static void handlePestCleaningFinished(Minecraft client) {
        PestReturnManager.handlePestCleaningFinished(client);
    }

    public static void update(Minecraft client) {
        checkTabListForPests(client, com.ihanuat.mod.MacroStateManager.getCurrentState());
    }

    public static void startCleaningSequence(Minecraft client, String plot) {
        currentInfestedPlot = plot;
        currentPestSessionId++;
        PestCleaningSequencer.startCleaningSequence(client, plot, currentInfestedPlot, currentPestSessionId);
    }

    public static void handlePhillipMessage(Minecraft client, String text) {
        PestBonusManager.handlePhillipMessage(client, text, currentInfestedPlot);
    }
}
