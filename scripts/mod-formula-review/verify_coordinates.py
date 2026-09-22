#!/usr/bin/env python3
"""Run the actual two source method bodies in an isolated Java boundary harness."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

source = Path(sys.argv[1]).read_text()
methods = []
for name in ('coordToChunkCoord', 'coordToRegionCoord'):
    match = re.search(r'public static int ' + name + r'\(final double coord\)\s*\{[^}]*\}', source)
    if not match:
        raise SystemExit(f'Method not found: {name}')
    methods.append(match.group())

java = '''
public class CoordinateRegression {
METHODS
    static int checks = 0;
    static void check(double x) {
        int actualChunk = coordToChunkCoord(x);
        int actualRegion = coordToRegionCoord(x);
        int expectedChunk = (int) Math.floor(x / 16.0);
        int expectedRegion = (int) Math.floor(x / 512.0);
        checks += 2;
        if (actualChunk != expectedChunk || actualRegion != expectedRegion) {
            throw new AssertionError("coordinate " + x + ": chunk=" + actualChunk
                + " expected=" + expectedChunk + ", region=" + actualRegion
                + " expected=" + expectedRegion);
        }
    }
    public static void main(String[] args) {
        double[] offsets = {-1, -0.999, -0.5, -0.001, 0, 0.001, 0.5, 0.999, 1};
        for (int boundary = -2048; boundary <= 2048; boundary++) {
            for (double offset : offsets) {
                check(boundary * 16.0 + offset);
                check(boundary * 512.0 + offset);
            }
        }
        for (double edge : new double[] {-29999984.0, 29999984.0}) {
            for (double offset : offsets) check(edge + offset);
        }
        for (double coordinate : new double[] {-4096.25, -128.5, -4, -0.125, 0, 4, 128.5, 4096.25}) {
            check(coordinate / 8.0);
            check(coordinate * 8.0);
        }
        System.out.println("PASS: " + checks + " chunk/region boundary assertions");
    }
}
'''.replace('METHODS', '\n'.join(methods))

with tempfile.TemporaryDirectory(prefix='xaeroplus-coordinate-regression-') as temporary:
    java_path = Path(temporary) / 'CoordinateRegression.java'
    java_path.write_text(java)
    subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/javac', str(java_path)], check=True)
    result = subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/java', '-cp', temporary, 'CoordinateRegression'])
    raise SystemExit(result.returncode)
