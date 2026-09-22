from pathlib import Path
import subprocess
import re
import tempfile
temporary = tempfile.TemporaryDirectory(prefix='xaero-distance-regression-')
out = Path(temporary.name)
root = Path(__file__).resolve().parents[3]
source = (root / 'XaeroDecompiled-26.2/worldmap/xaero/map/region/LeveledRegion.java').read_text()
fixed = (root / 'xaeroplus/common/src/main/java/xaeroplus/mixin/client/MixinLeveledRegion.java').read_text()
def method(text, name):
    pattern = r'(?:protected int|private void) ' + re.escape(name) + r'\([^)]*\) \{'
    found = re.search(pattern, text)
    assert found, name
    start = found.start()
    pos = found.end()
    depth = 1
    while depth:
        if text[pos] == '{': depth += 1
        elif text[pos] == '}': depth -= 1
        pos += 1
    return text[start:pos]
java = '''import java.util.Random;
public class RegionDistanceRegression {
    int regionX, regionZ, level;
    static int comparisonX, comparisonZ, comparisonLevel, comparisonLeafX, comparisonLeafZ;
    static int checks, baselineFailures;
    static class CallbackInfoReturnable<T> {
        T value;
        void setReturnValue(T v) { value = v; }
    }
'''
for name in ['distanceFromPlayer', 'leafDistanceFromPlayer', 'chunkDistanceFromPlayer']:
    java += method(source, name) + '\n'
    java += method(fixed, 'xaeroplus$' + name) + '\n'
java += '''
    static void verify(RegionDistanceRegression region, int x, int z, int lv, int cx, int cz, int cl, int lx, int lz) {
        region.regionX = x; region.regionZ = z; region.level = lv;
        comparisonX = cx; comparisonZ = cz; comparisonLevel = cl;
        comparisonLeafX = lx; comparisonLeafZ = lz;
        CallbackInfoReturnable<Integer> result = new CallbackInfoReturnable<>();
        region.xaeroplus$distanceFromPlayer(result);
        check("region", result.value,
            (int) Math.hypot(Math.floor(Math.scalb((double) x, lv - cl)) - cx,
                            Math.floor(Math.scalb((double) z, lv - cl)) - cz), region.distanceFromPlayer());
        region.xaeroplus$leafDistanceFromPlayer(result);
        check("leaf", result.value,
            (int) Math.hypot(Math.scalb((double) x, lv) - lx, Math.scalb((double) z, lv) - lz), region.leafDistanceFromPlayer());
        region.xaeroplus$chunkDistanceFromPlayer(result);
        check("chunk", result.value,
            (int) Math.hypot(Math.scalb((double) x, lv + 5) - cx, Math.scalb((double) z, lv + 5) - cz), region.chunkDistanceFromPlayer());
    }
    static void check(String kind, int actual, int expected, int baseline) {
        if (actual != expected) throw new AssertionError(kind + ": " + actual + " != " + expected);
        checks++;
        if (baseline != expected) baselineFailures++;
    }
    public static void main(String[] args) {
        RegionDistanceRegression r = new RegionDistanceRegression();
        verify(r, 50000, 0, 0, 0, 0, 0, 0, 0);
        System.out.println("25.6M-block separation, legacy leaf distance=" + r.leafDistanceFromPlayer() + ", fixed=50000 regions");
        verify(r, 1450, 0, 0, 0, 0, 0, 0, 0);
        System.out.println("742400-block separation, legacy chunk distance=" + r.chunkDistanceFromPlayer() + ", fixed=46400 chunks");
        // Axis boundary, diagonal overflow, negative coordinate shifts, opposite borders.
        for (int v : new int[]{0, 1, -1, 46340, 46341, -46341, 50000, -50000, 58593, -58594, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            for (int lv = 0; lv <= 3; lv++) {
                for (int cl = 0; cl <= 3; cl++) {
                    verify(r, v, -v, lv, 0, 0, cl, 0, 0);
                    verify(r, v, v, lv, 58593, -58594, cl, -58594, 58593);
                }
            }
        }
        Random random = new Random(0x2B2);
        for (int i = 0; i < 10000; i++) {
            int lv = random.nextInt(4), cl = random.nextInt(4);
            verify(r, random.nextInt(117188) - 58594, random.nextInt(117188) - 58594, lv,
                random.nextInt(3750000) - 1875000, random.nextInt(3750000) - 1875000, cl,
                random.nextInt(117188) - 58594, random.nextInt(117188) - 58594);
        }
        System.out.println("Passed " + checks + " source-extracted handler checks; legacy formula failures=" + baselineFailures);
    }
}
'''
(out / 'RegionDistanceRegression.java').write_text(java)
subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/javac', '-d', str(out), str(out / 'RegionDistanceRegression.java')], check=True)
subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/java', '-cp', str(out), 'RegionDistanceRegression'], check=True)
# Use expressions extracted from the proposed edits, not copies of the replacement formula.
baritone = (root / 'xaeroplus/common/src/main/java/xaeroplus/util/BaritoneExecutor.java').read_text()
gui = (root / 'xaeroplus/common/src/main/java/xaeroplus/mixin/client/MixinGuiMap.java').read_text()
expressions = [re.search(r'int goalX = (.*);', baritone).group(1), re.search(r'int x = (.*mouseBlockPosX.*);', gui).group(1)]
java = '''public class DimensionFloorRegression {
static int baritone(int x, double customDimDiv) { return %s; }
static int cursor(int mouseBlockPosX, double dimDiv) { return %s; }
public static void main(String[] args) {
 int[] input = {-30000000, -17, -16, -9, -8, -7, -1, 0, 1, 7, 8, 9, 17, 30000000};
 for (int x : input) {
  int expected = Math.floorDiv(x, 8);
  if (baritone(x, .125) != expected || cursor(x, 8) != expected) throw new AssertionError("divide " + x);
  if (baritone(x, 8) != x * 8 || cursor(x, .125) != x * 8) throw new AssertionError("multiply " + x);
  if (baritone(x, 1) != x) throw new AssertionError("same dimension " + x);
 }
 System.out.println("Passed 70 dimension-conversion boundary checks; -1 OW block maps to -1 Nether block");
}}
''' % tuple(expressions)
(out / 'DimensionFloorRegression.java').write_text(java)
subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/javac', '-d', str(out), str(out / 'DimensionFloorRegression.java')], check=True)
subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/java', '-cp', str(out), 'DimensionFloorRegression'], check=True)
