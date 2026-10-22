package com.PinkCats.bandwidthoptimizer.chunk.refresh;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;

/** Process-wide bounded metadata only. No packet, Channel, player or world ownership. */
public final class ChunkCoreClaims {
    public static final int GLOBAL_LIMIT=8192, PEER_LIMIT=1024, MAX_PEERS=1024;
    private static final long TTL_NANOS=300_000_000_000L;
    private static final LinkedHashMap<Key,Claim> CLAIMS=new LinkedHashMap<>();
    private static final HashMap<String,LinkedHashSet<Key>> PEERS=new HashMap<>();
    private record Key(String channel,long scope,int x,int z) {}
    public record Claim(String baselineHash,String coreHash,long observedNanos) {}
    private ChunkCoreClaims() {}
    public static boolean hash(String h) {
        if(h==null || h.length()!=64)return false;
        for(int i=0;i<64;i++){char c=h.charAt(i);if(!(c>='0'&&c<='9') && !(c>='a'&&c<='f'))return false;}return true;
    }
    public static synchronized void put(String channel,long scope,int x,int z,String baseline,String core) {
        if(channel==null || channel.length()>128 || scope<=0)return;
        Key key=new Key(channel,scope,x,z);remove(key);
        if(!hash(baseline) || !hash(core) || baseline.equals(core))return;
        if(!PEERS.containsKey(channel) && PEERS.size()>=MAX_PEERS)return;
        var keys=PEERS.get(channel);
        if(keys!=null && keys.size()>=PEER_LIMIT)remove(keys.iterator().next());
        CLAIMS.put(key,new Claim(baseline,core,System.nanoTime()));
        PEERS.computeIfAbsent(channel,ignored->new LinkedHashSet<>()).add(key);
        if(CLAIMS.size()>GLOBAL_LIMIT)remove(CLAIMS.keySet().iterator().next());
    }
    public static synchronized Claim get(String channel,long scope,int x,int z,String baseline) {
        Key key=new Key(channel,scope,x,z);Claim c=CLAIMS.get(key);
        if(c!=null && (System.nanoTime()-c.observedNanos>TTL_NANOS || !c.baselineHash.equals(baseline))){remove(key);return null;}return c;
    }
    private static void remove(Key key) {
        CLAIMS.remove(key);var keys=PEERS.get(key.channel);
        if(keys!=null){keys.remove(key);if(keys.isEmpty())PEERS.remove(key.channel);}
    }
    public static synchronized void clear(String channel) {
        var keys=PEERS.remove(channel);if(keys!=null)for(Key key:keys)CLAIMS.remove(key);
    }
    public static synchronized int size() { return CLAIMS.size(); }
}
