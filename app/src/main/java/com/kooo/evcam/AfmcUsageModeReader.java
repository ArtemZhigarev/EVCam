package com.kooo.evcam;

import android.os.SystemClock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * AppsForMyCar fork: follows the car's usage mode (0x21408030: 2 in use, 1 left, 0 going to sleep),
 * central lock and camping flag for sentry mode. Read-only: it listens to the vehicle HAL's property
 * stream on 127.0.0.1:40004 (no Android permission needed; the same channel and calls as upstream's
 * VhalSignalObserver) and asks the HAL to send every current value once per connection. It never
 * sets a property.
 *
 * Runs only while sentry mode is switched on. Every connection gets a new channel. A healthy stream
 * is closed and reopened every 2 minutes on purpose (that refreshes the reading); the "Channel
 * shutdownNow invoked" error our own close raises is not a failure and is not logged. When the HAL
 * can't be reached it retries with backoff (AfmcSentryPolicy.retryDelayMs), logs the error at most
 * every 10 minutes, and logs "vehicle signals back after N tries" when values arrive again.
 */
final class AfmcUsageModeReader {
    private static final String TAG = "AfmcSentry";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 40004;
    private static final String STREAM_METHOD = "vhal_proto.VehicleServer/StartPropertyValuesStream";
    private static final String SEND_ALL_METHOD = "vhal_proto.VehicleServer/SendAllPropertyValuesToStream";
    private static final long STREAM_REFRESH_MS = 120_000;

    private volatile boolean running;
    private volatile Integer usage;
    private volatile Integer lock;
    private volatile Integer camping;
    private volatile long readAtMs;
    private volatile ManagedChannel channel;
    private volatile Thread thread;
    private final Object wake = new Object();

    // Connection health. failedTries counts connections in a row that brought no values.
    private volatile int failedTries;
    private volatile boolean gotData;
    private volatile String lastError;
    private volatile long lastErrorLoggedMs;
    private volatile long startedAtMs;

    synchronized void start() {
        if (running) return;
        running = true;
        startedAtMs = SystemClock.elapsedRealtime();
        failedTries = 0;
        lastErrorLoggedMs = 0;
        thread = new Thread(this::loop, "AfmcUsageMode");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        shutdown();
        if (thread != null) thread.interrupt();
        thread = null;
        usage = null;
        lock = null;
        camping = null;
        readAtMs = 0;
    }

    /** Cuts a retry wait short (sentry was just switched on). */
    void retryNow() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    /** The latest usage mode, or null when none has been read yet. */
    Integer usage() {
        return usage;
    }

    /** Central lock and camping flag, last values seen (null until seen). */
    Integer lock() {
        return lock;
    }

    Integer camping() {
        return camping;
    }

    /** When usage() was last read (SystemClock.elapsedRealtime), 0 if never. */
    long readAtMs() {
        return readAtMs;
    }

    /** When start() last started the reader (SystemClock.elapsedRealtime). */
    long startedAtMs() {
        return startedAtMs;
    }

    /** Connections in a row that brought no values (0 while the stream is healthy). */
    int failedTries() {
        return failedTries;
    }

    private void loop() {
        while (running && thread == Thread.currentThread()) {
            gotData = false;
            lastError = null;
            try {
                streamOnce();
            } catch (InterruptedException e) {
                if (!running) break;
            } catch (Throwable t) {
                lastError = String.valueOf(t.getMessage());
            }
            shutdown();
            if (!running) break;
            long delay;
            try {
                if (gotData) {
                    // A planned refresh, or a stream that dropped after values had come (HAL restarting).
                    if (lastError != null) noteFailure("vehicle HAL stream dropped (" + lastError + "); reconnecting");
                    delay = AfmcSentryPolicy.RETRY_FIRST_MS;
                } else {
                    if (lastError == null) lastError = "the stream sent nothing";
                    failedTries++;
                    noteFailure(null);
                    delay = AfmcSentryPolicy.retryDelayMs(failedTries);
                }
            } catch (Throwable t) {
                delay = AfmcSentryPolicy.RETRY_MAX_MS;
            }
            try {
                synchronized (wake) {
                    if (running) wake.wait(delay);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Logs a failure, rate-limited so a car without the HAL doesn't flood the log. */
    private void noteFailure(String dropped) {
        long now = SystemClock.elapsedRealtime();
        if (!AfmcSentryPolicy.shouldLogFailure(dropped != null ? 1 : failedTries, now, lastErrorLoggedMs)) return;
        lastErrorLoggedMs = now;
        if (dropped != null) {
            AppLog.w(TAG, dropped);
        } else if (failedTries <= 1) {
            AppLog.w(TAG, "no vehicle signals from the HAL on :" + PORT + " (is this a Geely EX2?): "
                    + lastError + "; retrying");
        } else {
            AppLog.w(TAG, "still no vehicle signals after " + failedTries + " tries (last: " + lastError
                    + "); retrying every " + AfmcSentryPolicy.retryDelayMs(failedTries) / 1000 + " s");
        }
    }

    private void onValues() {
        if (gotData) return;
        gotData = true;
        int tries = failedTries;
        if (tries > 0) {
            failedTries = 0;
            lastErrorLoggedMs = 0;
            AppLog.i(TAG, "vehicle signals back after " + tries + (tries == 1 ? " try" : " tries"));
        }
    }

    private void streamOnce() throws InterruptedException {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER), UUID.randomUUID().toString());
        headers.put(Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER),
                "afmc_sentry_" + UUID.randomUUID().toString().substring(0, 8));
        ManagedChannel ch = OkHttpChannelBuilder.forAddress(HOST, PORT)
                .usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                .build();
        channel = ch;

        final CountDownLatch done = new CountDownLatch(1);
        // Set before we close the channel ourselves: the error that raises is not a failure.
        final AtomicBoolean closing = new AtomicBoolean(false);
        MethodDescriptor<byte[], byte[]> stream = method(STREAM_METHOD, MethodDescriptor.MethodType.SERVER_STREAMING);
        try {
            ClientCalls.asyncServerStreamingCall(ch.newCall(stream, CallOptions.DEFAULT), new byte[0],
                    new StreamObserver<byte[]>() {
                        @Override
                        public void onNext(byte[] value) {
                            try {
                                onValues();
                                Integer l = AfmcVhalProps.findInt32(value, AfmcSentryPolicy.LOCK_PROP);
                                if (l != null) lock = l;
                                Integer c = AfmcVhalProps.findInt32(value, AfmcSentryPolicy.CAMPING_PROP);
                                if (c != null) camping = c;
                                Integer v = AfmcVhalProps.findInt32(value, AfmcSentryPolicy.USAGE_MODE_PROP);
                                if (v == null) return;
                                Integer before = usage;
                                usage = v;
                                readAtMs = SystemClock.elapsedRealtime();
                                if (before == null || !before.equals(v)) {
                                    AppLog.d(TAG, "car usage mode " + before + " -> " + v);
                                }
                            } catch (Throwable t) {
                                AppLog.w(TAG, "vehicle HAL value not read: " + t);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            if (!closing.get() && running) lastError = String.valueOf(t.getMessage());
                            done.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            if (!closing.get() && running) lastError = "the HAL closed the stream";
                            done.countDown();
                        }
                    });

            // The HAL ties this request to the stream through session_id; give the stream a moment to register.
            for (int attempt = 1; attempt <= 3 && running && done.getCount() > 0; attempt++) {
                Thread.sleep(500L * attempt);
                try {
                    ClientCalls.blockingUnaryCall(ch.newCall(
                            method(SEND_ALL_METHOD, MethodDescriptor.MethodType.UNARY),
                            CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS)), new byte[0]);
                    break;
                } catch (Exception e) {
                    if (lastError == null) lastError = "asking for current values: " + e.getMessage();
                }
            }
            done.await(STREAM_REFRESH_MS, TimeUnit.MILLISECONDS);
        } finally {
            closing.set(true);
        }
    }

    private void shutdown() {
        ManagedChannel ch = channel;
        channel = null;
        if (ch == null) return;
        try {
            ch.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    private static MethodDescriptor<byte[], byte[]> method(String name, MethodDescriptor.MethodType type) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(type)
                .setFullMethodName(name)
                .setRequestMarshaller(Bytes.INSTANCE)
                .setResponseMarshaller(Bytes.INSTANCE)
                .build();
    }

    private enum Bytes implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
