package com.PinkCats.bandwidthoptimizer.integration.watut;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class WatutFrameReuseRegressionMain {
    public static void main(String[] args) throws Exception {
        clientTransitions();
        completeFrameDelivery();
        nativeFallbackAndLimits();
        capacityAndExpiry();
        System.out.println("WATUT frame reuse PASS: exact image restoration, newcomer-only replay, self delivery, transitions, FIFO fallback, lifecycle and hard bounds");
    }

    private static void clientTransitions() {
        WatutClientFrameReuse reuse = new WatutClientFrameReuse();
        Object world = new Object(), screen = new Object();
        UUID viewer = UUID.randomUUID();
        ByteBuffer first = ByteBuffer.wrap(new byte[]{1, 2, 3});
        check(!reuse.suppress(world, screen, first, 4, 4, Set.of(viewer), 1), "first frame");
        ByteBuffer repeated = ByteBuffer.wrap(new byte[]{1, 2, 3});
        check(reuse.suppress(world, screen, repeated, 4, 4, Set.of(viewer), 12), "identical fresh capture");
        check(repeated.position() == 0 && first.position() == 0, "sender buffer untouched");
        check(!reuse.suppress(world, screen, repeated, 4, 4, Set.of(viewer), 13), "explicit cached replay");
        check(!reuse.suppress(world, screen, ByteBuffer.wrap(new byte[]{1,2,3}), 4, 4, Set.of(viewer, UUID.randomUUID()), 14), "new viewer");
        check(!reuse.suppress(world, new Object(), ByteBuffer.wrap(new byte[]{1,2,3}), 4, 4, Set.of(viewer), 15), "screen switch");
        check(!reuse.suppress(new Object(), screen, ByteBuffer.wrap(new byte[]{1,2,3}), 4, 4, Set.of(viewer), 16), "world switch");
        reuse.reset();
        check(!reuse.suppress(world, screen, first, 4, 4, Set.of(viewer), 1), "reset");
        check(!reuse.suppress(world, screen, ByteBuffer.wrap(new byte[]{1,2,3}), 8, 4, Set.of(viewer), 2), "resize");
        check(!reuse.suppress(world, screen, ByteBuffer.wrap(new byte[]{1,2,3}), 8, 4, Set.of(viewer), 202), "bounded refresh");
        check(!reuse.suppress(world, screen, ByteBuffer.wrap(new byte[]{1,2,4}), 8, 4, Set.of(viewer), 203), "changed image");
    }

    private static void completeFrameDelivery() throws Exception {
        WatutFrameStore<byte[]> store = new WatutFrameStore<>();
        Sink sink = new Sink();
        Object source = new Object(), scope = new Object(), first = new Object(), newcomer = new Object();
        byte[] pixels = new byte[256 * 256 * 4];
        new java.util.Random(41).nextBytes(pixels);
        byte[] compressed = compress(pixels);
        List<byte[]> packets = parts(compressed);
        WatutFrameStore.Header header = new WatutFrameStore.Header(256, 256, pixels.length + 512, packets.size());
        send(store, source, scope, header, packets, List.of(source, first), 1, sink);
        check(sink.images.size() == 2 && Arrays.equals(pixels, inflate(join(sink.images.get(source)))), "self image restored");
        check(Arrays.equals(pixels, inflate(join(sink.images.get(first)))), "viewer image restored");
        sink.images.clear();
        send(store, source, scope, header, packets, List.of(source, first, newcomer), 2, sink);
        check(sink.images.size() == 1 && sink.images.containsKey(newcomer), "only newcomer gets complete image");
        check(sink.saved == compressed.length * 2L, "payload saving stage");
        check(Arrays.equals(pixels, inflate(join(sink.images.get(newcomer)))), "newcomer exact restore");
        sink.images.clear();
        store.replay(source, scope, new Object(), 3, sink);
        check(sink.images.size() == 1, "login/reconnect fresh connection replay");
        sink.images.clear();
        store.replay(source, new Object(), new Object(), 3, sink);
        check(sink.images.isEmpty(), "no cross-dimension login replay");
        byte[] changed = compressed.clone(); changed[changed.length - 1] ^= 1;
        send(store, source, scope, header, parts(changed), List.of(source, first), 4, sink);
        check(sink.images.size() == 2, "late changed fragment does not suppress earlier fragments");
        sink.images.clear();
        send(store, source, new Object(), header, packets, List.of(source, first), 5, sink);
        check(sink.images.size() == 2, "source scope invalidates equality");
        store.remove(source, sink);
        check(store.retainedBytes() == 0 && store.sourceCount() == 0, "close releases frame");
    }

    private static void nativeFallbackAndLimits() {
        WatutFrameStore<byte[]> store = new WatutFrameStore<>(); Sink sink = new Sink();
        Object source = new Object(), scope = new Object();
        WatutFrameStore.Header header = new WatutFrameStore.Header(8, 8, 100, 3);
        byte[] first = {1}, second = {2}, last = {3};
        check(store.receive(source, scope, header, 0, first, first, List.of(source), 1, sink), "hold first");
        check(store.receive(source, scope, header, 1, second, second, List.of(source), 1, sink), "hold second");
        check(!store.receive(source, scope, header, 1, last, last, List.of(source), 1, sink), "invalid index native");
        check(sink.nativePackets.equals(List.of(first, second)), "buffered original FIFO before current native");
        check(store.retainedBytes() == 0, "invalid release");
        store.receive(source, scope, header, 0, first, first, List.of(source), 2, sink);
        store.expire(13, Set.of(source), sink);
        check(sink.nativePackets.get(2) == first && store.retainedBytes() == 0, "timeout restores original");
        check(!store.receive(source, scope, new WatutFrameStore.Header(8,8,100,65), 0, first, first, List.of(source), 14, sink), "fragment count cap");
        check(!store.receive(source, scope, header, 0, new byte[WatutFrameStore.MAX_FRAME_BYTES+1], first, List.of(source), 14, sink), "frame size cap");
        check(!store.receive(source, scope, header, 0, first, first, java.util.Collections.nCopies(257, source), 14, sink), "recipient cap");
        store.receive(source, scope, header, 0, first, first, List.of(source), 20, sink);
        store.flush(source, sink);
        check(sink.nativePackets.get(3) == first && store.retainedBytes() == 0, "state ordering barrier");
    }

    private static void capacityAndExpiry() {
        WatutFrameStore<byte[]> store = new WatutFrameStore<>(); Sink sink = new Sink(); Object scope = new Object();
        Set<Object> live = new java.util.HashSet<>();
        byte[] body = new byte[900 * 1024];
        WatutFrameStore.Header header = new WatutFrameStore.Header(512,512,body.length,1);
        for (int i=0; i<1000; i++) {
            Object source = new Object(); live.add(source);
            store.receive(source, scope, header, 1, body, body, List.of(), 1, sink);
            check(store.retainedBytes() <= WatutFrameStore.MAX_TOTAL_BYTES, "global byte cap");
            check(store.sourceCount() <= WatutFrameStore.MAX_SOURCES, "source cap");
        }
        store.expire(602, live, sink);
        check(store.retainedBytes() == 0 && store.sourceCount() == 0, "TTL releases all");
        for (int i=0; i<1000; i++) {
            Object source = new Object(); byte[] small = {1};
            store.receive(source, scope, new WatutFrameStore.Header(1,1,4,1),1,small,small,List.of(),1,sink);
        }
        check(store.sourceCount() == WatutFrameStore.MAX_SOURCES, "independent source cap");
        store.expire(2, Set.of(), sink);
        check(store.retainedBytes() == 0 && store.sourceCount() == 0, "disconnect releases all");
        store.clear();
        WatutTrafficStats.reset(); WatutTrafficStats.clientSuppressed(10); WatutTrafficStats.serverSuppressed(10,3);
        check(WatutTrafficStats.snapshot().clientSavedPayloadBytes()==10 && WatutTrafficStats.snapshot().serverSavedPayloadBytes()==30, "upload/downlink counters separate");
    }

    private static void send(WatutFrameStore<byte[]> store, Object source, Object scope, WatutFrameStore.Header header,
                             List<byte[]> parts, List<?> viewers, long tick, Sink sink) {
        for (int i=0;i<parts.size();i++) check(store.receive(source,scope,header,parts.size()==1?1:i,parts.get(i),parts.get(i),viewers,tick,sink), "frame accepted");
    }
    private static List<byte[]> parts(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        for(int i=0;i<data.length;i+=31000) result.add(Arrays.copyOfRange(data,i,Math.min(data.length,i+31000)));
        return result;
    }
    private static byte[] join(List<byte[]> parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); for(byte[] part:parts) bytes.writeBytes(part); return bytes.toByteArray();
    }
    private static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION); deflater.setInput(data); deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] temp = new byte[4096];
        while(!deflater.finished()) output.write(temp,0,deflater.deflate(temp)); deflater.end(); return output.toByteArray();
    }
    private static byte[] inflate(byte[] data) throws Exception {
        Inflater inflater = new Inflater(); inflater.setInput(data); ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] temp = new byte[4096];
        while(!inflater.finished()) { int n=inflater.inflate(temp); check(n>0,"valid compressed frame"); output.write(temp,0,n); } inflater.end(); return output.toByteArray();
    }
    private static void check(boolean condition, String message) { if(!condition) throw new AssertionError(message); }
    private static final class Sink implements WatutFrameStore.Delivery<byte[]> {
        List<byte[]> nativePackets = new ArrayList<>(); Map<Object,List<byte[]>> images = new HashMap<>(); long saved;
        public void nativePacket(Object source, byte[] packet) { nativePackets.add(packet); }
        public void frame(Object source,Object recipient,List<byte[]> packets) { images.put(recipient,List.copyOf(packets)); }
        public void suppressed(long bytes,int recipients) { saved+=bytes*recipients; }
    }
}
