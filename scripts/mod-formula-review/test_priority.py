from pathlib import Path
import re, subprocess, sys, tempfile
source=Path(sys.argv[1]).read_text()
start=source.index('    private float getSectionPriority(')
pos=source.index('{', start)
depth=1
end=pos+1
while depth:
 if source[end]=='{': depth+=1
 if source[end]=='}': depth-=1
 end+=1
method=source[start:end]
constants='\n'.join(re.findall(r'    static final float .*?;',source))
code='''public class PriorityRegression {
'''+constants+'''
    private float invMaxDistance = 3.0f / 512.0f;
    private long creationTime = 5_000_000_000L;
    private int cameraX, cameraY, cameraZ;
    private record RenderSection(int getCenterX, int getCenterY, int getCenterZ, long getPendingUpdateSince) {}
    private static class ChunkUpdateTypes { static float getPriorityValue(int type) { return type; } }
'''+method+'''
    private static void close(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.00001f) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        var test = new PriorityRegression();
        for (int distance : new int[] {0, 25, 50, 100, 512}) {
            float expected = 3.0f * distance / 512.0f + (distance < 50 ? 0.6f * distance / 50.0f : 0.6f);
            close(test.getSectionPriority(new RenderSection(distance, 0, 0, test.creationTime), 0, false), expected);
        }
        var distant = new RenderSection(512, 0, 0, test.creationTime);
        var near = new RenderSection(100, 0, 0, test.creationTime);
        if (test.getSectionPriority(distant, 0, true) >= test.getSectionPriority(near, 0, false)) throw new AssertionError("bounded proximity must preserve the visible-chunk bias");
        close(test.getSectionPriority(distant, 0, true), test.getSectionPriority(distant, 0, false)-3.0f);
        close(test.getSectionPriority(new RenderSection(512, 0, 0, 0), 0, false), test.getSectionPriority(distant, 0, false)-1.0f);
        System.out.println("PASS Sodium priority: 0/25/50/100/512-block distances, visible-task ordering, waiting-time weight");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='sodium-priority-') as tmp:
 p=Path(tmp)/'PriorityRegression.java';p.write_text(code)
 subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/javac','-d',tmp,str(p)],check=True)
 subprocess.run(['/usr/lib/jvm/java-25-openjdk/bin/java','-cp',tmp,'PriorityRegression'],check=True)
