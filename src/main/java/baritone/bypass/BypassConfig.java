package baritone.bypass;

import java.util.*;

/**
 * Konfiguracja parametrów modułu Bypass / kopania GrimAC-safe.
 */
public class BypassConfig {

    public int yLevel = -55;

    // Tunnel
    public String pattern = "branch";
    public int minLengthBeforeTurn = 15;
    public int maxLengthBeforeTurn = 30;
    public double branchChance = 0.15;
    public int height = 2;
    public int width = 1;

    // Ores & Vein Mining
    public int oreRadius = 5;
    public boolean dynamicOreRadius = true;
    public boolean veinMining = true;
    public int veinMaxSize = 16;
    public int veinSearchDepth = 3;
    public List<String> priority = Arrays.asList("diamond", "gold", "iron", "redstone", "lapis", "emerald", "coal");
    public Map<String, List<String>> oreBlocks = new LinkedHashMap<>();

    // Safety & ESP Player Detection (GrimAC-safe: cicha detekcja bez obracania celownika)
    public boolean espPlayerDetect = true;
    public double playerWarningRadius = 60.0;
    public double playerDangerRadius = 20.0;
    public boolean checkCombatLog = true;
    public List<String> friendList = new ArrayList<>();

    // Moby
    public double mobRadius = 16.0;
    public double mobStop = 8.0;
    public double mobRetreat = 4.0;
    public double mobResume = 12.0;
    public double healthThreshold = 10.0;
    public double mobDcDistance = 20.0;
    public boolean lavaAvoid = true;
    public boolean waterAvoid = true;

    // AutoTool & Durability Guard (Ochrona narzędzi z Exosware)
    public boolean autoTool = true;
    public int durabilityThreshold = 10;

    // Inventory Cleaner / AutoDrop (Ochrona przed zapchaniem EQ)
    public boolean autoDropTrash = true;
    public List<String> trashBlocks = new ArrayList<>(Arrays.asList(
            "cobblestone", "cobbled_deepslate", "tuff", "dirt", "granite", "diorite", "andesite", "gravel"
    ));

    // Anticheat
    public String rotationStyle = "gcd_smooth";
    public double rotationSpeed = 360.0;
    public double jitter = 0.1;
    public int breakTimeVarianceMs = 50;
    public double movementVariance = 0.02;
    public double reachLimit = 4.5;
    public int preRotationTicks = 2;
    public int postRotationTicks = 2;

    // Reconnect
    public boolean reconnectEnabled = true;
    public int delayMs = 3000;
    public boolean autoResume = true;
    public String saveFile = "reconnect_data.json";

    public BypassConfig() {
        oreBlocks.put("diamond", Arrays.asList("diamond_ore", "deepslate_diamond_ore"));
        oreBlocks.put("gold", Arrays.asList("gold_ore", "deepslate_gold_ore"));
        oreBlocks.put("iron", Arrays.asList("iron_ore", "deepslate_iron_ore"));
        oreBlocks.put("redstone", Arrays.asList("redstone_ore", "deepslate_redstone_ore"));
        oreBlocks.put("lapis", Arrays.asList("lapis_ore", "deepslate_lapis_ore"));
        oreBlocks.put("emerald", Arrays.asList("emerald_ore", "deepslate_emerald_ore"));
        oreBlocks.put("coal", Arrays.asList("coal_ore", "deepslate_coal_ore"));
    }

    public List<String> getOreBlockNames(String oreCategory) {
        return oreBlocks.getOrDefault(oreCategory.toLowerCase(Locale.ROOT), Collections.emptyList());
    }

    public Set<String> getAvailableOreKeys() {
        return Collections.unmodifiableSet(oreBlocks.keySet());
    }
}
