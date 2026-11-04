/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.cache;

import baritone.cache.RegionRetentionPolicy.Candidate;
import it.unimi.dsi.fastutil.longs.LongList;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegionRetentionPolicyTest {

    private static final long MB = 1024 * 1024;
    private static final long NOW = 1_000_000_000L;
    private static final long IDLE = 180_000;

    @Test
    public void dirtyRegionsAreNeverSelected() {
        List<Candidate> regions = List.of(
                new Candidate(1, NOW - 10 * IDLE, true, 27 * MB),
                new Candidate(2, NOW - 10 * IDLE, true, 27 * MB));
        assertTrue(RegionRetentionPolicy.select(regions, 0, IDLE, NOW, false).isEmpty());
        assertTrue(RegionRetentionPolicy.select(regions, 0, IDLE, NOW, true).isEmpty());
    }

    @Test
    public void idleCleanRegionsGoRegardlessOfBudget() {
        List<Candidate> regions = List.of(
                new Candidate(1, NOW - IDLE - 1, false, 27 * MB),
                new Candidate(2, NOW - 1_000, false, 27 * MB));
        LongList victims = RegionRetentionPolicy.select(regions, 1024 * MB, IDLE, NOW, false);
        assertEquals(1, victims.size());
        assertEquals(1, victims.getLong(0));
    }

    @Test
    public void overBudgetEvictsLeastRecentlyUsedFirstUntilUnderBudget() {
        List<Candidate> regions = List.of(
                new Candidate(1, NOW - 60_000, false, 27 * MB),
                new Candidate(2, NOW - 30_000, false, 27 * MB),
                new Candidate(3, NOW - 90_000, false, 27 * MB),
                new Candidate(4, NOW - 5_000, false, 27 * MB));
        LongList victims = RegionRetentionPolicy.select(regions, 60 * MB, IDLE, NOW, false);
        assertEquals(List.of(3L, 1L), victims);
    }

    @Test
    public void regionsTouchedWithinGraceStayEvenWhenOverBudget() {
        List<Candidate> regions = List.of(
                new Candidate(1, NOW - 500, false, 27 * MB),
                new Candidate(2, NOW - 100, false, 27 * MB));
        assertTrue(RegionRetentionPolicy.select(regions, 0, IDLE, NOW, false).isEmpty());
    }

    @Test
    public void dirtyBytesStillCountAgainstTheBudget() {
        List<Candidate> regions = List.of(
                new Candidate(1, NOW - 10_000, true, 100 * MB),
                new Candidate(2, NOW - 10_000, false, 27 * MB));
        LongList victims = RegionRetentionPolicy.select(regions, 110 * MB, IDLE, NOW, false);
        assertEquals(List.of(2L), victims);
        assertFalse(victims.contains(1L));
    }

    @Test
    public void everythingSelectsEveryCleanRegion() {
        List<Candidate> regions = List.of(
                new Candidate(1, NOW, false, 27 * MB),
                new Candidate(2, NOW, true, 27 * MB),
                new Candidate(3, NOW - 1, false, 27 * MB));
        assertEquals(List.of(1L, 3L), RegionRetentionPolicy.select(regions, 1024 * MB, IDLE, NOW, true));
    }
}
