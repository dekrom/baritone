"""Compile and exercise the sibling Sodium/Voxy production algorithms."""
from pathlib import Path
import os
import subprocess
import tempfile

work = Path(__file__).resolve().parent
root = work.parents[2]
gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
jar_root = gradle_home / 'caches/modules-2/files-2.1'
classpath = os.pathsep.join(str(next((jar_root / dependency).rglob('*.jar'))) for dependency in (
    'it.unimi.dsi/fastutil/8.5.18', 'org.jspecify/jspecify/1.0.0'))
java = Path('/usr/lib/jvm/java-25-openjdk/bin')
sodium = root / 'sodium/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk'
subprocess.run(['python3', str(work / 'test_priority.py'),
                str(sodium / 'lists/TaskCollectingTree.java')], check=True)
with tempfile.TemporaryDirectory(prefix='rendering-formulas-') as temporary:
    sources = [sodium / 'compile/estimation' / (name + '.java') for name in (
        'Estimator', 'Abstract2DLinearEstimator', 'UploadDuration',
        'UploadDurationEstimator', 'ExpDecayLinear2DEstimator')]
    sources.extend((root / 'voxy/src/main/java/me/cortex/voxy/common/util/HierarchicalBitSet.java',
                    work / 'AllocatorRegression.java', work / 'EstimatorRegression.java'))
    subprocess.run([str(java / 'javac'), '-cp', classpath, '-d', temporary,
                    *map(str, sources)], check=True)
    for test in ('AllocatorRegression', 'EstimatorRegression'):
        subprocess.run([str(java / 'java'), '-cp', temporary + os.pathsep + classpath, test], check=True)
