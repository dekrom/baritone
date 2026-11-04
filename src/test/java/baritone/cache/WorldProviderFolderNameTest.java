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

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class WorldProviderFolderNameTest {

    @Test
    public void hostnamesAreUntouched() {
        assertEquals("2b2t.org", WorldProvider.sanitizeFileName("2b2t.org"));
        assertEquals("realms", WorldProvider.sanitizeFileName("realms"));
    }

    @Test
    public void directIpMatchesWhatWindowsAlwaysProduced() {
        assertEquals("217.77.15.230_25571", WorldProvider.sanitizeFileName("217.77.15.230:25571"));
    }

    @Test
    public void everyWindowsReservedCharacterIsReplaced() {
        assertEquals("a_b_c_d_e_f_g_h_i", WorldProvider.sanitizeFileName("a\\b/c:d*e?f\"g<h>i"));
        assertEquals("x_y", WorldProvider.sanitizeFileName("x|y"));
        assertEquals("tab_", WorldProvider.sanitizeFileName("tab\t"));
    }

    @Test
    public void trailingDotsAndSpacesAreStripped() {
        assertEquals("host_1", WorldProvider.sanitizeFileName("host:1. "));
        assertEquals("_", WorldProvider.sanitizeFileName("..."));
    }

    @Test
    public void existingRawDirectoryKeepsBeingUsed() throws IOException {
        Path dir = Files.createTempDirectory("baritone-folder-test");
        try {
            assertEquals("host_25565", WorldProvider.serverFolderName(dir, "host:25565"));
            Files.createDirectory(dir.resolve("host:25565"));
            assertEquals("host:25565", WorldProvider.serverFolderName(dir, "host:25565"));
        } finally {
            Files.deleteIfExists(dir.resolve("host:25565"));
            Files.delete(dir);
        }
    }
}
