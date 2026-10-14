package com.PinkCats.bandwidthoptimizer.integration.createsifter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SifterSyncTransactionRegressionMain {
    public static void main(String[] args) throws Exception {
        Object a=new Object(),b=new Object(); AtomicInteger sends=new AtomicInteger();
        require(!SifterSyncTransaction.defer(a),"outside transaction");
        var before=SifterSyncTransaction.snapshot();
        SifterSyncTransaction.run(a,()->{
            for(int i=0;i<10;i++)require(SifterSyncTransaction.defer(a),"same owner");
            require(!SifterSyncTransaction.defer(b),"unrelated owner");
            SifterSyncTransaction.run(a,()->require(SifterSyncTransaction.defer(a),"nested same owner"),()->{throw new AssertionError("nested flush");});
        },sends::incrementAndGet);
        require(sends.get()==1&&!SifterSyncTransaction.defer(a),"one flush and cleanup");
        require(SifterSyncTransaction.snapshot().savedCalls()-before.savedCalls()==10,"net calls");
        SifterSyncTransaction.run(a,()->{},()->{throw new AssertionError("empty flush");});
        RuntimeException original=new RuntimeException("operation"),flush=new RuntimeException("flush");
        try { SifterSyncTransaction.run(a,()->{SifterSyncTransaction.defer(a);throw original;},()->{sends.incrementAndGet();throw flush;});throw new AssertionError("exception hidden"); }
        catch(RuntimeException expected){require(expected==original&&expected.getSuppressed()[0]==flush,"original exception preserved");}
        require(!SifterSyncTransaction.defer(a),"exception cleanup");
        SifterSyncTransaction.run(a,()->{
            SifterSyncTransaction.run(b,()->{require(SifterSyncTransaction.defer(b),"nested other");require(SifterSyncTransaction.defer(a),"parent identity");},sends::incrementAndGet);
        },sends::incrementAndGet);
        AtomicReference<Throwable> workerFailure=new AtomicReference<>();
        SifterSyncTransaction.run(a,()->{
            Thread worker=new Thread(()->{try{require(!SifterSyncTransaction.defer(a),"thread isolation");}catch(Throwable t){workerFailure.set(t);}});
            worker.start();try{worker.join();}catch(InterruptedException e){throw new RuntimeException(e);}
        },()->{throw new AssertionError("worker leaked state");});
        require(workerFailure.get()==null,"worker assertion");
        recurse(0);
        CreateSifterSyncCompat.apply(null,sends::incrementAndGet);
        CreateSifterSyncCompat.apply(new Object(),sends::incrementAndGet);
        require(!CreateSifterSyncCompat.defer(a),"optional absent passthrough");
        FakeCycle fake=new FakeCycle();CreateSifterSyncCompat.invokeApply(fake);require(fake.count==1,"original apply exactly once");
        FakeOverride child=new FakeOverride();CreateSifterSyncCompat.invokeApply(child);require(child.count==2,"virtual override");
        try { CreateSifterSyncCompat.invokeApply(new FailingCycle());throw new AssertionError("original apply failure hidden"); }
        catch (RuntimeException expected) { require(expected==FailingCycle.FAILURE,"original apply cause"); }
        var sectionMethod=Class.forName("com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReportCollector").getDeclaredMethod("sifterSection");
        sectionMethod.setAccessible(true);
        var section=(com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReport.Section)sectionMethod.invoke(null);
        require(section.id().equals("create_sifter")&&section.metrics().size()==5,"report counters");
        System.out.println("Sifter transaction regression PASS: owner/thread/nesting/cap/empty/failure/absent/report");
    }
    private static void recurse(int depth) {
        Object owner=new Object();
        SifterSyncTransaction.run(owner,()->{require(SifterSyncTransaction.defer(owner)==(depth<8),"bounded nesting");if(depth<12)recurse(depth+1);},()->{});
        require(!SifterSyncTransaction.defer(owner),"bounded nesting cleanup");
    }
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    private static class FakeCycle { int count; protected void apply(){count++;} }
    private static final class FakeOverride extends FakeCycle { @Override protected void apply(){count+=2;} }
    private static final class FailingCycle { static final RuntimeException FAILURE=new RuntimeException("native failure"); protected void apply(){throw FAILURE;} }
}
