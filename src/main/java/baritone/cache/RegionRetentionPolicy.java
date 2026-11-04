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

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which cached regions to drop from RAM. Pure so it can be unit tested; CachedWorld applies the result under
 * its locks and re-checks the dirty flag there.
 * <p>
 * A dirty region is never selected. Clean regions idle for longer than {@code idleMs} are always selected.
 * If the resident bytes still exceed the budget, the least recently used clean regions go next, except those
 * touched within {@link #GRACE_MS}: the packer or a path calculation is about to use them again.
 */
final class RegionRetentionPolicy {

    static final long GRACE_MS = 2_000;

    record Candidate(long id, long lastAccessMs, boolean dirty, long bytes) {}

    private RegionRetentionPolicy() {}

    static LongList select(List<Candidate> regions, long budgetBytes, long idleMs, long nowMs, boolean everything) {
        LongList victims = new LongArrayList();
        long resident = 0;
        for (Candidate c : regions) {
            resident += c.bytes();
        }
        List<Candidate> clean = new ArrayList<>();
        for (Candidate c : regions) {
            if (c.dirty()) {
                continue;
            }
            if (everything || nowMs - c.lastAccessMs() > idleMs) {
                victims.add(c.id());
                resident -= c.bytes();
            } else {
                clean.add(c);
            }
        }
        if (resident <= budgetBytes) {
            return victims;
        }
        clean.sort(Comparator.comparingLong(Candidate::lastAccessMs));
        for (Candidate c : clean) {
            if (resident <= budgetBytes || nowMs - c.lastAccessMs() < GRACE_MS) {
                break;
            }
            victims.add(c.id());
            resident -= c.bytes();
        }
        return victims;
    }
}
