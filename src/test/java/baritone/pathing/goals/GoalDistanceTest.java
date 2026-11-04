package baritone.pathing.goals;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalDistanceTest {

    @Test
    public void nearDoesNotAcceptDistantPositionsAfterIntegerOverflow() {
        GoalNear goal = new GoalNear(new BlockPos(0, 64, 0), 2);
        assertTrue(goal.isInGoal(2, 64, 0));
        assertFalse(goal.isInGoal(3, 64, 0));
        for (int distance : new int[]{50_000, 65_536, 30_000_000}) {
            assertFalse(goal.isInGoal(distance, 64, 0));
            assertFalse(goal.isInGoal(0, 64, -distance));
        }
    }

    @Test
    public void nearSupportsRadiiWhoseSquaresExceedAnInteger() {
        GoalNear goal = new GoalNear(new BlockPos(-1_000_000, 64, 1_000_000), 50_000);
        assertTrue(goal.isInGoal(-1_000_000, 64, 1_000_000));
        assertTrue(goal.isInGoal(-950_000, 64, 1_000_000));
        assertFalse(goal.isInGoal(-949_999, 64, 1_000_000));
    }

    @Test
    public void runawayDistanceWorksAcrossTheWorld() {
        GoalRunAway goal = new GoalRunAway(100, new BlockPos(0, 64, 0));
        assertFalse(goal.isInGoal(99, 64, 0));
        assertTrue(goal.isInGoal(100, 64, 0));
        assertTrue(goal.isInGoal(50_000, 64, 0));
        assertTrue(goal.isInGoal(0, 64, -65_536));
        assertTrue(new GoalRunAway(100, new BlockPos(-30_000_000, 64, 0))
                .isInGoal(30_000_000, 64, 0));
    }

    @Test
    public void runawayPreservesLargeAndFractionalRequestedDistances() {
        GoalRunAway goal = new GoalRunAway(100_000, new BlockPos(0, 64, 0));
        assertFalse(goal.isInGoal(99_999, 64, 0));
        assertTrue(goal.isInGoal(100_000, 64, 0));
        assertFalse(new GoalRunAway(1.1, new BlockPos(0, 64, 0)).isInGoal(1, 64, 0));
    }

    @Test
    public void runawayStillChecksEveryOriginAndRequestedHeight() {
        GoalRunAway goal = new GoalRunAway(10, Integer.valueOf(70),
                new BlockPos(0, 64, 0), new BlockPos(100, 64, 0));
        assertFalse(goal.isInGoal(95, 70, 0));
        assertFalse(goal.isInGoal(50, 69, 0));
        assertTrue(goal.isInGoal(50, 70, 0));
    }

    @Test
    public void equalRunawayGoalsWithoutHeightHaveEqualHashes() {
        GoalRunAway first = new GoalRunAway(1.1, new BlockPos(0, 64, 0));
        GoalRunAway second = new GoalRunAway(1.1, new BlockPos(0, 64, 0));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
