package com.kooo.evcam;

import android.os.SystemClock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * AppsForMyCar fork: follows the car's usage mode (0x21408030: 2 in use, 1 left, 0 going to sleep)
 * for sentry mode. Read-only: it listens to the vehicle HAL's property stream on 127.0.0.1:40004
 * (no Android permission needed; the same channel and calls as upstream's VhalSignalObserver) and
 * asks the HAL to send every current value once per connection. It never sets a property.
 *
 * Runs only while sentry mode is switched on. Reconnects every 2 minutes (like VhalSignalObserver),
 * which also refreshes the reading.
 */
final class AfmcUsageModeReader {
    private static final String TAG = "AfmcSentry";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 40004;
    private static final String STREAM_METHOD = "vhal_proto.VehicleServer/StartPropertyValuesStream";
    private static final String SEND_ALL_METHOD = "vhal_proto.VehicleServer/SendAllPropertyValuesToStream";
    private static final long RECONNECT_DELAY_MS = 5_000;
    private static final long STREAM_REFRESH_MS = 120_000;

    private volatile boolean running;
    private volatile Integer usage;
    private volatile long readAtMs;
    private volatile ManagedChannel channel;
    private Thread thread;
    private boolean saidNoHal;

    synchronized void start() {
        if (running) return;
        running = true;
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
        readAtMs = 0;
    }

    /** The latest usage mode, or null when none has been read yet. */
    Integer usage() {
        return usage;
    }

    /** When usage() was last read (SystemClock.elapsedRealtime), 0 if never. */
    long readAtMs() {
        return readAtMs;
    }

    private void loop() {
        while (running) {
            try {
                streamOnce();
            } catch (Throwable t) {
                AppLog.w(TAG, "usage mode stream: " + t.getMessage());
            }
            shutdown();
            if (!running) break;
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
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
        MethodDescriptor<byte[], byte[]> stream = method(STREAM_METHOD, MethodDescriptor.MethodType.SERVER_STREAMING);
        ClientCalls.asyncServerStreamingCall(ch.newCall(stream, CallOptions.DEFAULT), new byte[0],
                new StreamObserver<byte[]>() {
                    @Override
                    public void onNext(byte[] value) {
                        Integer v = AfmcVhalProps.findInt32(value, AfmcSentryPolicy.USAGE_MODE_PROP);
                        if (v == null) return;
                        Integer before = usage;
                        usage = v;
                        readAtMs = SystemClock.elapsedRealtime();
                        if (before == null || !before.equals(v)) {
                            AppLog.d(TAG, "car usage mode " + before + " -> " + v);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        if (!saidNoHal) {
                            saidNoHal = true;
                            AppLog.w(TAG, "vehicle HAL stream error (is this a Geely EX2?): " + t.getMessage());
                        }
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        done.countDown();
                    }
                });

        // The HAL ties this request to the stream through session_id; give the stream a moment to register.
        for (int attempt = 1; attempt <= 3 && running; attempt++) {
            Thread.sleep(500L * attempt);
            try {
                ClientCalls.blockingUnaryCall(ch.newCall(
                        method(SEND_ALL_METHOD, MethodDescriptor.MethodType.UNARY),
                        CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS)), new byte[0]);
                saidNoHal = false;
                break;
            } catch (Exception e) {
                AppLog.w(TAG, "asking for current values failed (" + attempt + "/3): " + e.getMessage());
            }
        }
        done.await(STREAM_REFRESH_MS, TimeUnit.MILLISECONDS);
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
