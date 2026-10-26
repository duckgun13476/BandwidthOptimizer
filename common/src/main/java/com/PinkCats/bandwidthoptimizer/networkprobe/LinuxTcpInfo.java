package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional Linux data-segment denominator over the exact owned epoll descriptor. */
final class LinuxTcpInfo {
    private static final String SHA256="6e69fd5e8c5310f22d1d30c02003d74e9cbe2cda3ad2910542c65fe4df98fdb1";
    private static final String RESOURCE="/META-INF/bandwidthoptimizer/native/bo-tcp-info-linux-x86_64.so";
    private static final AtomicBoolean STARTED=new AtomicBoolean();
    private static volatile boolean loaded;
    private LinuxTcpInfo() {}
    static void prepare() {
        String arch=System.getProperty("os.arch","");
        if(!System.getProperty("os.name","").equals("Linux") || !(arch.equals("amd64") || arch.equals("x86_64")) || !STARTED.compareAndSet(false,true)) return;
        Thread loader=new Thread(LinuxTcpInfo::load,"bo-linux-tcp-info-loader");loader.setDaemon(true);loader.start();
    }
    private static void load() {
        Path temporary=null;
        try(InputStream input=LinuxTcpInfo.class.getResourceAsStream(RESOURCE)) {
            if(input==null) return;
            byte[] bytes=input.readNBytes(256*1024+1);
            if(bytes.length>256*1024 || !hash(bytes).equals(SHA256)) return;
            Path directory=BandwidthOptimizerOutputPaths.resolve("native/network-probe").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            if(!directory.toRealPath().equals(directory)) return;
            Path target=directory.resolve("bo-tcp-info-"+SHA256+".so");
            if(Files.isSymbolicLink(target)) return;
            if(!Files.exists(target)) {
                temporary=Files.createTempFile(directory,"bo-tcp-info-",".tmp");Files.write(temporary,bytes);
                try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);} catch(FileAlreadyExistsException concurrent) { /* Validate the final shared file. */ }
            }
            if(Files.size(target)!=bytes.length || !hash(Files.readAllBytes(target)).equals(SHA256)) return;
            System.load(target.toString());loaded=true;
        } catch(IOException | RuntimeException | LinkageError ignored) { /* Existing epoll metrics remain available; denominator stays unmeasured. */ }
        finally {if(temporary!=null) try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    static void append(Channel channel,JsonObject metrics) {
        prepare();
        if(!loaded || !channel.eventLoop().inEventLoop() || !channel.isActive() || !channel.getClass().getName().equals("io.netty.channel.epoll.EpollSocketChannel")) return;
        try {
            // Invoke public API owners: AbstractEpollChannel/LinuxSocket themselves are package-private.
            Object descriptor=Class.forName("io.netty.channel.unix.UnixChannel").getMethod("fd").invoke(channel);
            int fd=((Number)Class.forName("io.netty.channel.unix.FileDescriptor").getMethod("intValue").invoke(descriptor)).intValue();
            long[] values=new long[2];
            if(query(fd,values)==0) {
                metrics.addProperty("total_sent_segments",values[0]);metrics.addProperty("total_retransmitted_segments",values[1]);
                metrics.addProperty("retransmission_rate_basis","data_segments_including_retransmissions");
            }
        } catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* No invented zero or guessed descriptor. */ }
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static native int query(int descriptor,long[] output);
}
