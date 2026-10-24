package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only Winsock adapter; extraction/loading is always outside the network event loop. */
final class WindowsTcpInfo {
    private static final String SHA256="d4da492926e655127679c21f67ae017a6e9226a2570e2dffb42a194b4c7fa4ac";
    private static final String RESOURCE="/META-INF/bandwidthoptimizer/native/bo-tcp-info-windows-x86_64.dll";
    private static final AtomicBoolean STARTED=new AtomicBoolean();
    private static volatile String state="not_initialized",error="";
    private static volatile Method javaChannel;
    private WindowsTcpInfo() {}
    static boolean applicable() {
        String arch=System.getProperty("os.arch","");
        int java=Runtime.version().feature();
        return System.getProperty("os.name","").startsWith("Windows")
                && (arch.equals("amd64") || arch.equals("x86_64"))
                && (java==17 || java==21 || java==25);
    }
    static void prepare() {
        if(!applicable() || !STARTED.compareAndSet(false,true)) return;
        state="initializing";
        Thread loader=new Thread(WindowsTcpInfo::load,"bo-tcp-info-loader");
        loader.setDaemon(true); loader.setContextClassLoader(WindowsTcpInfo.class.getClassLoader()); loader.start();
    }
    private static void load() {
        Path temporary=null;
        try(InputStream input=WindowsTcpInfo.class.getResourceAsStream(RESOURCE)) {
            if(input==null) throw new IOException("Native resource missing");
            byte[] bytes=input.readNBytes(256*1024+1);
            if(bytes.length>256*1024 || !hash(bytes).equals(SHA256)) throw new IOException("Native resource checksum mismatch");
            Path directory=BandwidthOptimizerOutputPaths.resolve("native/network-probe").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path target=directory.resolve("bo-tcp-info-"+SHA256+".dll").normalize();
            if(!target.getParent().equals(directory)) throw new IOException("Invalid native target");
            if(Files.isSymbolicLink(directory) || Files.isSymbolicLink(target)) throw new IOException("Native path is symbolic");
            if(!Files.exists(target)) {
                temporary=Files.createTempFile(directory,"bo-tcp-info-",".tmp");
                Files.write(temporary,bytes);
                try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); }
                catch(FileAlreadyExistsException concurrent) { /* Recheck the exact final bytes below. */ }
            }
            if(Files.size(target)!=bytes.length || !hash(Files.readAllBytes(target)).equals(SHA256))
                throw new IOException("Extracted native checksum mismatch");
            System.load(target.toString());
            Method accessor=NioSocketChannel.class.getDeclaredMethod("javaChannel");
            accessor.setAccessible(true); javaChannel=accessor;
            state="supported";
        } catch(IOException | ReflectiveOperationException | RuntimeException | LinkageError failure) {
            error=failure.getClass().getSimpleName(); state="unavailable";
        } finally {
            if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(IOException ignored) {}
        }
    }
    static JsonObject sample(Channel channel) {
        JsonObject result=new JsonObject();
        result.addProperty("provider","windows_sio_tcp_info_v0");
        if(!applicable()) { result.addProperty("status","unsupported"); return result; }
        prepare();
        result.addProperty("status",state);
        if(!state.equals("supported")) { if(!error.isEmpty()) result.addProperty("reason",error); return result; }
        if(!(channel instanceof NioSocketChannel) || !channel.eventLoop().inEventLoop()) {
            result.addProperty("status","unsupported"); result.addProperty("reason","requires_owned_nio_event_loop"); return result;
        }
        try {
            SocketChannel socket=(SocketChannel)javaChannel.invoke(channel);
            if(!socket.getClass().getName().equals("sun.nio.ch.SocketChannelImpl") || !socket.isOpen()
                    || !Objects.equals(socket.getLocalAddress(),channel.localAddress())
                    || !Objects.equals(socket.getRemoteAddress(),channel.remoteAddress())) {
                result.addProperty("status","unavailable"); result.addProperty("reason","socket_identity_not_confirmed"); return result;
            }
            long[] values=new long[15];
            int code=query(socket,values);
            if(code!=0) { result.addProperty("status","unavailable"); result.addProperty("native_error_code",code); return result; }
            String[] names={"tcp_state","connection_time_ms","rtt_us","min_rtt_us","bytes_in_flight",
                    "send_congestion_window_bytes","send_window_bytes","receive_window_bytes","total_bytes_out",
                    "total_bytes_in","total_retransmitted_bytes","fast_retransmit_calls","duplicate_acks_in",
                    "timeout_episodes","syn_retransmissions"};
            for(int i=0;i<names.length;i++) result.addProperty(names[i],values[i]);
        } catch(ReflectiveOperationException | IOException | RuntimeException | LinkageError failure) {
            result.addProperty("status","unavailable"); result.addProperty("reason",failure.getClass().getSimpleName());
        }
        return result;
    }
    private static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static native int query(SocketChannel channel,long[] output);
}
