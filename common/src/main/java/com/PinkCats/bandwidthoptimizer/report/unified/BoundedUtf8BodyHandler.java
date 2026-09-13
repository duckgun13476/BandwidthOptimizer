package com.PinkCats.bandwidthoptimizer.report.unified;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class BoundedUtf8BodyHandler implements HttpResponse.BodyHandler<String> {
    private final int maxBytes;

    BoundedUtf8BodyHandler(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo responseInfo) {
        return new Subscriber(maxBytes);
    }

    static HttpResponse.BodySubscriber<String> subscriber(int maxBytes) {
        return new Subscriber(maxBytes);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<String> {
        private final byte[] bytes;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int length;
        private boolean done;

        private Subscriber(int maxBytes) {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            this.bytes = new byte[maxBytes];
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            if (candidate == null) {
                fail(new NullPointerException("subscription"));
                return;
            }
            if (subscription != null || done) {
                candidate.cancel();
                return;
            }
            subscription = candidate;
            candidate.request(1L);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int remaining = buffer.remaining();
                if (remaining > bytes.length - length) {
                    fail(new IOException("Upload response exceeds the " + bytes.length + " byte safety limit."));
                    return;
                }
                buffer.get(bytes, length, remaining);
                length += remaining;
            }
            if (!done) {
                subscription.request(1L);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            fail(throwable);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            body.complete(new String(bytes, 0, length, StandardCharsets.UTF_8));
        }

        private void fail(Throwable throwable) {
            if (done) {
                return;
            }
            done = true;
            if (subscription != null) {
                subscription.cancel();
            }
            body.completeExceptionally(throwable);
        }
    }
}
