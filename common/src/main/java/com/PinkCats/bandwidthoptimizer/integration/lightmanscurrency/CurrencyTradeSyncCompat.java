package com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.server.level.ServerPlayer;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ServerPlayerLevelCompat;
import java.util.UUID;

public final class CurrencyTradeSyncCompat {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.compat.currencyBankSyncBatchEnabled", "true"));
    private static final String BASE = "io.github.lightman314.lightmanscurrency.api.traders.";
    private static final String ITEM = "io.github.lightman314.lightmanscurrency.common.traders.item.ItemTraderData";
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            try {
                Class<?> trader = Class.forName(BASE+"TraderData",false,type.getClassLoader());
                Class<?> context = Class.forName(BASE+"TradeContext",false,type.getClassLoader());
                Method trade = trader.getDeclaredMethod("TryExecuteTrade",context,int.class);
                Method client = trader.getDeclaredMethod("isClient");
                Method player = context.getDeclaredMethod("getPlayer");
                return new Access(trade,client,player);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) { throw new IllegalStateException("Matched LC trade invocation has an incompatible API",exception); }
        }
    };
    private static final ClassValue<BankAccess> BANK_ACCESS = new ClassValue<>() {
        @Override protected BankAccess computeValue(Class<?> type) {
            if (!type.getName().equals("io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache")) return new BankAccess(null,null);
            try {
                Method method=type.getDeclaredMethod("syncBankAccount",UUID.class,ServerPlayer.class);method.setAccessible(true);
                Class<?> data=Class.forName("io.github.lightman314.lightmanscurrency.api.misc.data.CustomData",false,type.getClassLoader());
                return new BankAccess(method,data.getDeclaredMethod("isClient"));
            } catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) {return new BankAccess(null,null);}
        }
    };
    private CurrencyTradeSyncCompat() {}
    public static Object execute(Object trader,Object context,int index) {
        Access a = ACCESS.get(trader.getClass());
        if (!ENABLED || !trader.getClass().getName().equals(ITEM) || Boolean.TRUE.equals(invoke(a.client,trader))) return invoke(a.trade,trader,context,index);
        Object player = invoke(a.player,context);
        if (!(player instanceof ServerPlayer serverPlayer)) return invoke(a.trade,trader,context,index);
        var level=ServerPlayerLevelCompat.serverLevel(serverPlayer);
        if (level==null || level.getServer()==null || !level.getServer().isSameThread()) return invoke(a.trade,trader,context,index);
        return CurrencyBankSyncTransaction.run(trader,()->invoke(a.trade,trader,context,index));
    }
    public static boolean deferBank(Object cache,UUID account,ServerPlayer target) {
        if (!ENABLED) return false;
        BankAccess access=BANK_ACCESS.get(cache.getClass());
        if (target!=null || access.sync==null || Boolean.TRUE.equals(invoke(access.client,cache))) {CurrencyBankSyncTransaction.barrier();return false;}
        return CurrencyBankSyncTransaction.defer(cache,account,()->invoke(access.sync,cache,account,null));
    }
    private static Object invoke(Method method,Object target,Object... arguments) {
        try { return method.invoke(target,arguments); }
        catch (InvocationTargetException exception) {
            Throwable failure = exception.getCause();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("LC synchronization operation failed",failure);
        } catch (ReflectiveOperationException exception) {throw new IllegalStateException("LC synchronization invocation failed",exception);}
    }
    private record Access(Method trade,Method client,Method player) {}
    private record BankAccess(Method sync,Method client) {}
}
