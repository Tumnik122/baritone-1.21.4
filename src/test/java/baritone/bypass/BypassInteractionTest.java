package baritone.bypass;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class BypassInteractionTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void observesNextTickBreakExactlyOnceWithOriginalOreState() {
        BypassBreakTracker tracker = new BypassBreakTracker();
        BlockState ore = Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
        BlockPos pos = new BlockPos(1, -55, 0);
        tracker.watch(pos, ore);
        tracker.attacked();
        assertNull(tracker.poll(p -> ore));
        BypassBreakTracker.BrokenBlock broken = tracker.poll(p -> Blocks.AIR.defaultBlockState());
        assertNotNull(broken);
        assertEquals(pos, broken.pos());
        assertEquals(ore, broken.state());
        assertNull(tracker.poll(p -> Blocks.AIR.defaultBlockState()));
        assertFalse(tracker.isTracking());
    }

    @Test
    public void blocksRemovedWithoutOurAttackDoNotCountAsMined() {
        BypassBreakTracker tracker = new BypassBreakTracker();
        tracker.watch(BlockPos.ZERO, Blocks.DIAMOND_ORE.defaultBlockState());
        assertNull(tracker.poll(p -> Blocks.AIR.defaultBlockState()));
        assertFalse(tracker.isTracking());
    }

    @Test
    public void switchingTargetsAndStoppingClearPreviousAttack() {
        BypassBreakTracker tracker = new BypassBreakTracker();
        tracker.watch(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        tracker.attacked();
        tracker.watch(BlockPos.ZERO.above(), Blocks.DIAMOND_ORE.defaultBlockState());
        assertNull(tracker.poll(p -> Blocks.AIR.defaultBlockState()));
        tracker.watch(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        tracker.attacked();
        tracker.reset();
        assertNull(tracker.poll(p -> Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void waitingOnSameBlockDoesNotForgetTheAttack() {
        BypassBreakTracker tracker = new BypassBreakTracker();
        BlockState ore = Blocks.DIAMOND_ORE.defaultBlockState();
        tracker.watch(BlockPos.ZERO, ore);
        tracker.attacked();
        tracker.watch(BlockPos.ZERO, ore);
        assertNotNull(tracker.poll(p -> Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void slowMiningGetsLongerThanTheOldFiveSecondTimeout() {
        assertEquals(5000L, BypassBreakTracker.timeoutMillis(0.1F));
        assertTrue(BypassBreakTracker.timeoutMillis(0.005F) >= 21000L);
        assertEquals(120000L, BypassBreakTracker.timeoutMillis(0.000001F));
        assertEquals(5000L, BypassBreakTracker.timeoutMillis(0));
        assertEquals(5000L, BypassBreakTracker.timeoutMillis(Float.NaN));
    }

    @Test
    public void doesNotMineDiamondOreWithAnEmptyHandOrWoodenPickaxe() {
        BypassConfig config = new BypassConfig();
        assertFalse(AutoToolManager.isUsable(ItemStack.EMPTY, Blocks.DIAMOND_ORE.defaultBlockState(), config));
        assertFalse(AutoToolManager.isUsable(new ItemStack(Items.WOODEN_PICKAXE),
                Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), config));
        assertTrue(AutoToolManager.isUsable(ItemStack.EMPTY, Blocks.DIRT.defaultBlockState(), config));
    }

    @Test
    public void durabilityGuardDoesNotFallBackToAnAlmostBrokenTool() {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        BypassConfig config = new BypassConfig();
        tool.setDamageValue(tool.getMaxDamage() - 10);
        assertFalse(AutoToolManager.isUsable(tool, Blocks.DIRT.defaultBlockState(), config));
        tool.setDamageValue(tool.getMaxDamage() - 11);
        assertTrue(AutoToolManager.isUsable(tool, Blocks.DIRT.defaultBlockState(), config));
    }

    @Test
    public void fullInventoryCanStillMergeTheMatchingOreDrop() {
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 63);
        assertTrue(InventoryCleaner.canAccept(diamonds, new ItemStack(Items.DIAMOND, 3)));
        diamonds.setCount(64);
        assertFalse(InventoryCleaner.canAccept(diamonds, new ItemStack(Items.DIAMOND)));
        assertFalse(InventoryCleaner.canAccept(new ItemStack(Items.COBBLESTONE, 1), new ItemStack(Items.DIAMOND)));
        assertTrue(InventoryCleaner.canAccept(ItemStack.EMPTY, new ItemStack(Items.DIAMOND)));
    }

    @Test
    public void differentItemComponentsCannotBeMerged() {
        ItemStack named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Named diamond"));
        assertFalse(InventoryCleaner.canAccept(named, new ItemStack(Items.DIAMOND)));
    }

    @Test
    public void whitelistProtectsResourcesPickaxesAndFood() {
        BypassConfig config = new BypassConfig();

        // 1. Kilofy i narzędzia
        assertTrue(InventoryCleaner.isPickaxeOrTool(new ItemStack(Items.DIAMOND_PICKAXE)));
        assertTrue(InventoryCleaner.isPickaxeOrTool(new ItemStack(Items.NETHERITE_PICKAXE)));
        assertTrue(InventoryCleaner.isPickaxeOrTool(new ItemStack(Items.IRON_PICKAXE)));
        assertFalse(InventoryCleaner.isTrash(new ItemStack(Items.DIAMOND_PICKAXE), config, null));

        // 2. Jedzenie
        assertTrue(InventoryCleaner.isFood(new ItemStack(Items.COOKED_BEEF)));
        assertTrue(InventoryCleaner.isFood(new ItemStack(Items.GOLDEN_APPLE)));
        assertTrue(InventoryCleaner.isFood(new ItemStack(Items.BREAD)));
        assertFalse(InventoryCleaner.isTrash(new ItemStack(Items.COOKED_BEEF), config, null));

        // 3. Surowce (żelazo, diamenty, złoto, miedź, sztabki, węgiel)
        assertTrue(InventoryCleaner.isResource(new ItemStack(Items.RAW_IRON), null));
        assertTrue(InventoryCleaner.isResource(new ItemStack(Items.RAW_GOLD), null));
        assertTrue(InventoryCleaner.isResource(new ItemStack(Items.RAW_COPPER), null));
        assertTrue(InventoryCleaner.isResource(new ItemStack(Items.DIAMOND), null));
        assertTrue(InventoryCleaner.isResource(new ItemStack(Items.IRON_INGOT), null));
        assertTrue(InventoryCleaner.isResource(new ItemStack(Items.COAL), null));
        assertFalse(InventoryCleaner.isTrash(new ItemStack(Items.RAW_IRON), config, null));
        assertFalse(InventoryCleaner.isTrash(new ItemStack(Items.DIAMOND), config, null));

        // 4. Śmieci do wyrzucenia (bruk, łupek, ziemia, żwir itp.)
        assertTrue(InventoryCleaner.isTrash(new ItemStack(Items.COBBLESTONE), config, null));
        assertTrue(InventoryCleaner.isTrash(new ItemStack(Items.COBBLED_DEEPSLATE), config, null));
        assertTrue(InventoryCleaner.isTrash(new ItemStack(Items.TUFF), config, null));
        assertTrue(InventoryCleaner.isTrash(new ItemStack(Items.DIRT), config, null));
        assertTrue(InventoryCleaner.isTrash(new ItemStack(Items.GRAVEL), config, null));
        assertTrue(InventoryCleaner.isTrash(new ItemStack(Items.ANDESITE), config, null));
    }

    @Test
    public void slotMappingMatchesVanillaInventoryLayout() {
        // Hotbar (0..8) maps to menu slots 36..44
        assertEquals(36, InventoryCleaner.invToMenuSlot(0));
        assertEquals(44, InventoryCleaner.invToMenuSlot(8));
        assertEquals(0, InventoryCleaner.menuToInvSlot(36));
        assertEquals(8, InventoryCleaner.menuToInvSlot(44));

        // Main backpack (9..35) maps to menu slots 9..35
        assertEquals(9, InventoryCleaner.invToMenuSlot(9));
        assertEquals(35, InventoryCleaner.invToMenuSlot(35));
        assertEquals(9, InventoryCleaner.menuToInvSlot(9));
        assertEquals(35, InventoryCleaner.menuToInvSlot(35));
    }

    @Test
    public void identifiesAllIronItemsCorrectlyForChestDeposit() {
        // True iron materials (both raw and smelted)
        assertTrue(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.RAW_IRON)));
        assertTrue(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.IRON_INGOT)));
        assertTrue(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.IRON_BLOCK)));
        assertTrue(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.RAW_IRON_BLOCK)));
        assertTrue(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.IRON_NUGGET)));

        // Non-iron items or tools/armor MUST NOT be deposited
        assertFalse(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.DIAMOND_PICKAXE)));
        assertFalse(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.IRON_PICKAXE)));
        assertFalse(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.IRON_SWORD)));
        assertFalse(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.COOKED_BEEF)));
        assertFalse(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.WATER_BUCKET)));
        assertFalse(baritone.process.HomeProcess.isIronItem(new ItemStack(Items.COBBLESTONE)));
        assertFalse(baritone.process.HomeProcess.isIronItem(ItemStack.EMPTY));
    }

    @Test
    public void identifiesAllMinedResourcesCorrectlyForChestDeposit() {
        // True mined minerals and ores
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.RAW_IRON)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.IRON_INGOT)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.DIAMOND)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.RAW_GOLD)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.GOLD_INGOT)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.RAW_COPPER)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.COAL)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.LAPIS_LAZULI)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.REDSTONE)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.EMERALD)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.ANCIENT_DEBRIS)));
        assertTrue(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.NETHERITE_SCRAP)));

        // Items that MUST NOT be deposited (tools, weapons, armor, food, buckets, torches, throwaways)
        assertFalse(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.DIAMOND_PICKAXE)));
        assertFalse(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.IRON_PICKAXE)));
        assertFalse(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.IRON_SWORD)));
        assertFalse(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.COOKED_BEEF)));
        assertFalse(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.WATER_BUCKET)));
        assertFalse(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.COBBLESTONE)));
        assertFalse(baritone.process.HomeProcess.isMinedResource(new ItemStack(Items.TORCH)));
        assertFalse(baritone.process.HomeProcess.isMinedResource(ItemStack.EMPTY));
    }
}


