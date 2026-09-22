import java.util.Random;
import me.cortex.voxy.common.util.HierarchicalBitSet;

public class AllocatorRegression {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static int firstRun(boolean[] used, int count) {
        for (int i = 0; i <= used.length - count; i++) {
            int j = 0;
            while (j < count && !used[i + j]) j++;
            if (j == count) return i;
        }
        return -1;
    }
    public static void main(String[] args) {
        var fragmented = new HierarchicalBitSet(16);
        for (int i = 0; i < 16; i++) fragmented.allocateNext();
        for (int i = 0; i < 16; i += 2) fragmented.free(i);
        check(fragmented.allocateNextConsecutiveCounted(2) < 0, "fragmentation must not allocate beyond limit");
        check(fragmented.getCount() == 8, "failed allocation must preserve count");
        var exact = new HierarchicalBitSet(16);
        check(exact.allocateNextConsecutiveCounted(16) == 0, "exact capacity must be usable");
        var fullWord = new HierarchicalBitSet(128);
        fullWord.allocateNext(); fullWord.allocateNext(); fullWord.free(0);
        check(fullWord.allocateNextConsecutiveCounted(64) == 2, "64-bit run must not overlap allocated ID 1");
        for (int limit : new int[] {1, 16, 63, 64, 65, 127, 128, 1024}) {
            var used = new boolean[limit];
            var allocator = new HierarchicalBitSet(limit);
            var random = new Random(limit);
            for (int t = 0; t < 10000; t++) {
                if (random.nextBoolean()) {
                    int id = random.nextInt(limit);
                    check(allocator.free(id) == used[id], "free state mismatch");
                    used[id] = false;
                } else {
                    int count = random.nextInt(64) + 1;
                    int expected = firstRun(used, count);
                    int actual = allocator.allocateNextConsecutiveCounted(count);
                    check(expected < 0 ? actual < 0 : expected == actual, "first-fit mismatch: limit=" + limit + " count=" + count + " expected=" + expected + " actual=" + actual);
                    if (actual >= 0) for (int j = 0; j < count; j++) used[actual + j] = true;
                }
                int usedCount = 0;
                for (int i = 0; i < limit; i++) {
                    check(allocator.isSet(i) == used[i], "occupancy mismatch");
                    if (used[i]) usedCount++;
                }
                check(allocator.getCount() == usedCount, "count mismatch");
            }
        }
        // Last free slot in the full 24-bit hierarchy cannot satisfy a two-slot run.
        var edge = new HierarchicalBitSet();
        for (int i = 0; i < edge.getLimit(); i++) edge.allocateNext();
        edge.free(edge.getLimit() - 1); edge.free(0); edge.free(2);
        check(edge.allocateNextConsecutiveCounted(2) < 0, "full hierarchy exhaustion must not access a nonexistent level");
        System.out.println("PASS Voxy allocator: fragmented limits, exact fit, 64-slot runs, 80,000 randomized operations, full 24-bit boundary");
    }
}
