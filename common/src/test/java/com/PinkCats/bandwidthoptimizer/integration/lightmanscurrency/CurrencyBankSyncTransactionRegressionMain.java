package com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency;

import java.util.*;

public final class CurrencyBankSyncTransactionRegressionMain {
    public static void main(String[] args) {
        Object owner=new Object(),cache=new Object(),otherCache=new Object();
        UUID account=new UUID(1,2),otherAccount=new UUID(3,4);
        List<String> output=new ArrayList<>(); int[] balance={0},logs={0};
        Runnable flush=()->{check(!CurrencyBankSyncTransaction.defer(cache,account,()->{}),"flush recursion");output.add("account="+balance[0]+",logs="+logs[0]);};
        CurrencyBankSyncTransaction.run(owner,()->{
            balance[0]++; check(CurrencyBankSyncTransaction.defer(cache,account,flush),"balance update");
            logs[0]++; check(CurrencyBankSyncTransaction.defer(cache,account,flush),"log update");
            CurrencyBankSyncTransaction.barrier(); output.add("notification");
            balance[0]++; CurrencyBankSyncTransaction.defer(cache,account,flush);
            return "result";
        });
        check(output.equals(List.of("account=1,logs=1","notification","account=2,logs=1")),"final balance/log and notification order");
        check(!CurrencyBankSyncTransaction.defer(cache,account,flush),"scope release");
        RuntimeException original=new IllegalStateException("original"),flushError=new IllegalStateException("flush");
        try {CurrencyBankSyncTransaction.run(owner,()->{CurrencyBankSyncTransaction.defer(cache,account,()->{throw flushError;});throw original;});throw new AssertionError("exception");}
        catch(RuntimeException failure) {check(failure==original && failure.getSuppressed()[0]==flushError,"preserve failures");}
        check(!CurrencyBankSyncTransaction.defer(cache,account,flush),"exception cleanup");
        output.clear();
        CurrencyBankSyncTransaction.run(owner,()->{
            CurrencyBankSyncTransaction.defer(cache,account,()->output.add("first"));
            CurrencyBankSyncTransaction.defer(cache,otherAccount,()->output.add("other account"));
            CurrencyBankSyncTransaction.defer(otherCache,otherAccount,()->output.add("other cache"));
            return null;
        });
        check(output.equals(List.of("first","other account","other cache")),"account/cache separation");
        long before=CurrencyBankSyncTransaction.snapshot().savedCalls();
        CurrencyBankSyncTransaction.run(owner,()->{for(int i=0;i<1000;i++)CurrencyBankSyncTransaction.defer(cache,account,()->{});return null;});
        check(CurrencyBankSyncTransaction.snapshot().savedCalls()-before==999,"exact accounting");
        System.out.println("CurrencyBankSyncTransactionRegressionMain PASS");
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
