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
}
