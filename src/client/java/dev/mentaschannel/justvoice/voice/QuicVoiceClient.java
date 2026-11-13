package dev.mentaschannel.justvoice.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.ConnectionListener;
import tech.kwik.core.ConnectionTerminatedEvent;
import tech.kwik.core.QuicClientConnection;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * QUIC client for the Just Voice transport protocol (ALPN just-voice/1).
 *
 * Control datagrams:
 *   0x01 + ASCII token = JOIN
 *   0x02              = JOINED
 *   0x03              = LEAVE
 * Voice datagrams:
 *   0x10 + uint32 sequence + uint32 timestamp + Opus payload
 * Server relay:
 *   0x11 + uint32 sequence + uint32 timestamp + Opus payload
 */
public final class QuicVoiceClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int MAX_DATAGRAM_BYTES = 1100;
    private static final byte OP_JOIN = 0x01;
    private static final byte OP_JOINED = 0x02;
    private static final byte OP_LEAVE = 0x03;
    private static final byte OP_VOICE = 0x10;
    private static final byte OP_VOICE_RELAY = 0x11;
    private static final byte OP_ERROR = 0x7f;

    private final VoiceCredentials credentials;
    private final Consumer<byte[]> voicePacketConsumer;
    private final Consumer<String> errorConsumer;
    private final CountDownLatch joinedLatch = new CountDownLatch(1);
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile QuicClientConnection connection;
    private volatile boolean joined;
    private volatile boolean closed;
    private volatile long lastVoicePacketSentNanos;

    public QuicVoiceClient(
            VoiceCredentials credentials,
            Consumer<byte[]> voicePacketConsumer,
            Consumer<String> errorConsumer
    ) {
        this.credentials = credentials;
        this.voicePacketConsumer = voicePacketConsumer;
        this.errorConsumer = errorConsumer;
    }

    public void connect() throws Exception {
        QuicClientConnection.Builder builder = QuicClientConnection.newBuilder()
                .host(credentials.host())
                .port(credentials.port())
                .applicationProtocol("just-voice/1")
                .connectTimeout(Duration.ofSeconds(10))
                .maxIdleTimeout(Duration.ofSeconds(120))
                .enableDatagramExtension();

        if (credentials.certificateFile() != null) {
            builder.customTrustManager(loadTrustManager(credentials.certificateFile()));
        }

        QuicClientConnection candidate = builder.build();
        connection = candidate;
        candidate.setDatagramHandler(this::handleDatagram);
        candidate.setConnectionListener(new ConnectionListener() {
            @Override
            public void disconnected(ConnectionTerminatedEvent event) {
                joined = false;
                if (!closed) {
                    String detail = "Voice connection closed ("
                            + (event.closedByPeer() ? "peer" : "local")
                            + ", " + event.closeReason()
                            + (event.hasError() ? ", " + event.errorDescription() : "")
                            + ")";
                    LOGGER.warn("QUIC connection terminated: {}", detail);
                    errorConsumer.accept(detail);
                    joinedLatch.countDown();
                }
            }
        });

        if (closed) {
            candidate.close();
            throw new IllegalStateException("Voice connection was cancelled");
        }

        candidate.connect();
        if (!candidate.isDatagramExtensionEnabled()) {
            throw new IllegalStateException("Voice server does not support QUIC DATAGRAM");
        }

        // Kwik's keepAlive(int) argument is the lifetime of its keep-alive
        // actor, not the PING interval. A value of 20 seconds can be shorter
        // than half the peer's negotiated idle timeout, causing the actor to
        // schedule no PING at all. Keep it active for the practical lifetime
        // of this voice session; Kwik sends PINGs at half the peer idle timeout.
        candidate.keepAlive(Integer.MAX_VALUE);

        byte[] token = credentials.accessToken().getBytes(StandardCharsets.US_ASCII);
        byte[] join = new byte[1 + token.length];
        join[0] = OP_JOIN;
        System.arraycopy(token, 0, join, 1, token.length);
        candidate.sendDatagram(join);

        if (!joinedLatch.await(10, TimeUnit.SECONDS) || !joined) {
            throw new IllegalStateException("Voice server did not accept the invitation");
        }
    }

    private static X509TrustManager loadTrustManager(java.nio.file.Path certificatePath) throws Exception {
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(certificatePath)) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
        }

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("just-voice-server", certificate);

        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
        );
        factory.init(trustStore);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No X.509 trust manager available"));
    }

    private void handleDatagram(byte[] data) {
        if (data == null || data.length == 0 || closed) {
            return;
        }

        byte opcode = data[0];
        if (opcode == OP_JOINED && data.length == 1) {
            joined = true;
            joinedLatch.countDown();
            LOGGER.info("Connected to Just Voice server.");
            return;
        }

        if (opcode == OP_VOICE_RELAY && data.length >= 10 && data.length <= MAX_DATAGRAM_BYTES) {
            voicePacketConsumer.accept(data);
            return;
        }

        if (opcode == OP_ERROR) {
            String reason;
            if (data.length == 2) {
                int errorCode = data[1] & 0xff;
                reason = switch (errorCode) {
                    case 0x01 -> "invalid or expired voice token";
                    case 0x02 -> "voice protocol violation";
                    case 0x03 -> "server does not support QUIC DATAGRAM";
                    default -> "unknown voice server error code " + errorCode;
                };
            } else {
                reason = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
                if (reason.isBlank()) {
                    reason = "voice server rejected the connection";
                }
            }
            errorConsumer.accept(reason);
            joinedLatch.countDown();
            return;
        }

        LOGGER.debug("Ignored unknown Just Voice datagram opcode 0x{}", Integer.toHexString(opcode & 0xff));
    }

    public void sendVoice(byte[] opusFrame) {
        QuicClientConnection current = connection;
        if (!joined || closed || current == null || !current.isConnected()
                || opusFrame == null || opusFrame.length == 0) {
            return;
        }

        int packetLength = 9 + opusFrame.length;
        if (packetLength > MAX_DATAGRAM_BYTES || packetLength > current.maxDatagramDataSize()) {
            LOGGER.warn("Skipped oversized Opus frame.");
            return;
        }

        byte[] packet = new byte[packetLength];
        packet[0] = OP_VOICE;
        putInt(packet, 1, sequence.getAndIncrement());
        putInt(packet, 5, (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        System.arraycopy(opusFrame, 0, packet, 9, opusFrame.length);
        current.sendDatagram(packet);
        lastVoicePacketSentNanos = System.nanoTime();
    }

    public boolean isTransmittingVoice() {
        long lastSent = lastVoicePacketSentNanos;
        return joined && !closed && lastSent != 0
                && System.nanoTime() - lastSent <= TimeUnit.MILLISECONDS.toNanos(750);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    public boolean isJoined() {
        return joined && !closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        QuicClientConnection current = connection;
        if (joined && current != null && current.isConnected()) {
            try {
                current.sendDatagram(new byte[]{OP_LEAVE});
            } catch (RuntimeException ignored) {
                // Closing is best-effort.
            }
        }

        closed = true;
        joined = false;
        joinedLatch.countDown();
        if (current != null) {
            current.close();
        }
    }
}
