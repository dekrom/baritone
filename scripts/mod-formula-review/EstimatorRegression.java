import java.util.ArrayList;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadDuration;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadDurationEstimator;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.ExpDecayLinear2DEstimator;
import java.util.HashMap;
import java.util.Map;

public class EstimatorRegression {
    private static void close(long actual, long expected, String label) {
        if (Math.abs(actual - expected) > 1) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
    private static class SmallEstimator extends ExpDecayLinear2DEstimator<Void> {
        SmallEstimator() { super(0.05, 100, 40, 12345L); }
        @Override protected <T> Map<Void,T> createMap() { return new HashMap<>(); }
    }
    private record Sample(long x, long y, double weight) {}
    private static long reference(List<Sample> points, long input) {
        double total=0, meanX=0, meanY=0;
        for (var p:points) { total+=p.weight; meanX+=p.weight*p.x; meanY+=p.weight*p.y; }
        meanX/=total; meanY/=total;
        double covariance=0, variance=0;
        for (var p:points) { covariance+=p.weight*(p.x-meanX)*(p.y-meanY); variance+=p.weight*(p.x-meanX)*(p.x-meanX); }
        double slope=variance==0?0:Math.max(0,covariance/variance);
        return (long)(meanY+slope*(input-meanX));
    }
    public static void main(String[] args) {
        var varying=new UploadDurationEstimator();
        for(int batch=0;batch<3;batch++) {
            for(int i=0;i<101;i++) varying.addData(new UploadDuration(1_000_000L+i*10_000L,1000L+i*10L));
            varying.updateModels();
            close(varying.estimateUploadDuration(1000L),1_000_000L,"oversized first batch "+batch);
        }
        var constant=new UploadDurationEstimator();
        for(int i=0;i<100;i++) constant.addData(new UploadDuration(1_000_000L,1000L));
        constant.updateModels();
        close(constant.estimateUploadDuration(1000L),1_000_000L,"constant-X initial batch");
        for(int i=0;i<100;i++) constant.addData(new UploadDuration(2_000_000L,2000L));
        constant.updateModels();
        close(constant.estimateUploadDuration(1500L),1_500_000L,"different constant-X batch establishes slope");

        var warming=new SmallEstimator();
        var points=new ArrayList<Sample>();
        for(int batch=0;batch<3;batch++) {
            for(int i=0;i<40;i++) {
                long x=1000L+batch*100L+i;
                long y=500_000L+10L*x+batch*10000L;
                warming.addData(new UploadDuration(y,x)); points.add(new Sample(x,y,1));
            }
            warming.updateModels();
            close(warming.predict(null,1100L),reference(points,1100L),"warmup and target crossing batch "+batch);
        }
        var shifted=new UploadDurationEstimator();
        var shiftedPoints=new ArrayList<Sample>();
        for(int batch=0;batch<2;batch++) {
            for(int i=0;i<100;i++) {
                long x=1000L+batch*1000L+(i%2)*10L;
                long y=500_000L+100L*x+batch*250_000L;
                shifted.addData(new UploadDuration(y,x));
                shiftedPoints.add(new Sample(x,y,batch==0?19:1));
            }
            shifted.updateModels();
        }
        for(long input:new long[]{1000,1500,2000}) close(shifted.estimateUploadDuration(input),reference(shiftedPoints,input),"shifted means at "+input);

        var deferred=new UploadDurationEstimator();
        for(int i=0;i<99;i++) deferred.addData(new UploadDuration(1_000_000L,1000L));
        deferred.updateModels(); close(deferred.estimateUploadDuration(1000),100_000L,"retain fallback while below batch minimum");
        deferred.addData(new UploadDuration(1_000_000L,1000L)); deferred.updateModels();
        close(deferred.estimateUploadDuration(1000),1_000_000L,"deferred samples retained");
        System.out.println("PASS Sodium estimator: >100 first batch, constant-X, warmup/target crossing, shifted-mean weighted least-squares reference, deferred samples");
    }
}
