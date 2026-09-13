package com.PinkCats.bandwidthoptimizer.report.unified;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

public final class BoundedUtf8BodyHandlerRegressionMain {
    private static final int LIMIT = 1024;

    private BoundedUtf8BodyHandlerRegressionMain() {
    }

    public static void main(String[] args) {
        acceptsProductionResponse();
        acceptsExactLimit();
        rejectsOneByteOverLimit();
        rejectsFragmentedOverflow();
        System.out.println("Bounded HTTP response regression passed.");
    }

    private static void acceptsProductionResponse() {
        String key = "A".repeat(24);
        String json = "{\"key\":\"" + key + "\",\"url\":\"https://bostats.torqueflux.com/report/" + key + "\"}";
        check(json.getBytes(StandardCharsets.UTF_8).length == 105, "production response fixture changed");
        check(json.equals(complete(List.of(bytes(json)))), "production response was not preserved");
    }

    private static void acceptsExactLimit() {
        byte[] body = new byte[LIMIT];
        Arrays.fill(body, (byte) 'x');
        check(complete(List.of(ByteBuffer.wrap(body))).length() == LIMIT, "exact response limit was rejected");
    }

    private static void rejectsOneByteOverLimit() {
        ProbeSubscription subscription = new ProbeSubscription();
        HttpResponse.BodySubscriber<String> subscriber = BoundedUtf8BodyHandler.subscriber(LIMIT);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[LIMIT + 1])));
        expectFailure(subscriber);
        check(subscription.cancelled, "oversized response did not cancel its subscription");
    }

    private static void rejectsFragmentedOverflow() {
        ProbeSubscription subscription = new ProbeSubscription();
        HttpResponse.BodySubscriber<String> subscriber = BoundedUtf8BodyHandler.subscriber(LIMIT);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[700])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[325])));
        expectFailure(subscriber);
        check(subscription.cancelled, "fragmented overflow did not cancel its subscription");
    }

    private static String complete(List<ByteBuffer> buffers) {
        HttpResponse.BodySubscriber<String> subscriber = BoundedUtf8BodyHandler.subscriber(LIMIT);
        subscriber.onSubscribe(new ProbeSubscription());
        subscriber.onNext(buffers);
        subscriber.onComplete();
        return subscriber.getBody().toCompletableFuture().join();
    }

    private static ByteBuffer bytes(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void expectFailure(HttpResponse.BodySubscriber<String> subscriber) {
        try {
            subscriber.getBody().toCompletableFuture().join();
            throw new IllegalStateException("oversized response was accepted");
        } catch (CompletionException expected) {
            check(expected.getCause() != null && expected.getCause().getMessage().contains("1024 byte safety limit"),
                    "unexpected overflow failure: " + expected);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class ProbeSubscription implements Flow.Subscription {
        private boolean cancelled;

        @Override
        public void request(long count) {
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
