package dev.mentaschannel.justvoice.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.ConnectionListener;
import tech.kwik.core.ConnectionTerminatedEvent;
import tech.kwik.core.QuicClientConnection;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * QUIC client for Just Voice protocol v2 (ALPN just-voice/2).
 *
 * Control datagrams:
 *   0x01 + ASCII token = JOIN
 *   0x02 + 8-byte id   = JOINED
 *   0x03              = LEAVE
 *   0x04              = ROSTER_REQUEST
 *   0x05              = PEER_PROFILE
 *   0x06              = PEER_LEFT
 *   0x08              = ROSTER_BEGIN
 *   0x09 + profile    = ROSTER_ENTRY
 *   0x0A              = ROSTER_END
 *
 * Voice:
 *   0x10 + sequence + timestamp + Opus = VOICE
 *   0x11 + peer id + sequence + timestamp + Opus = VOICE_RELAY
 */
public final class QuicVoiceClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int MAX_DATAGRAM_BYTES = 1100;
    private static final int PEER_ID_BYTES = 8;
    private static final int MIN_RELAY_BYTES = 1 + PEER_ID_BYTES + 8 + 1;

    private static final byte OP_JOIN = 0x01;
    private static final byte OP_JOINED = 0x02;
    private static final byte OP_LEAVE = 0x03;
    private static final byte OP_ROSTER_REQUEST = 0x04;
    private static final byte OP_PEER_PROFILE = 0x05;
    private static final byte OP_PEER_LEFT = 0x06;
    private static final byte OP_ROSTER_BEGIN = 0x08;
    private static final byte OP_ROSTER_ENTRY = 0x09;
    private static final byte OP_ROSTER_END = 0x0A;
    private static final byte OP_VOICE = 0x10;
    private static final byte OP_VOICE_RELAY = 0x11;
    private static final byte OP_ERROR = 0x7f;

    private final VoiceCredentials credentials;
    private final Consumer<IncomingVoiceFrame> voicePacketConsumer;
    private final Consumer<VoicePeerProfile> peerProfileConsumer;
    private final Consumer<Long> peerLeftConsumer;
    private final Consumer<List<VoicePeerProfile>> rosterConsumer;
    private final Consumer<String> errorConsumer;
    private final List<VoicePeerProfile> rosterBuffer = new ArrayList<>();
    private final CountDownLatch joinedLatch = new CountDownLatch(1);
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile QuicClientConnection connection;
    private volatile boolean joined;
    private volatile boolean closed;
    private volatile long selfPeerId;
    private volatile long lastVoicePacketSentNanos;

    public QuicVoiceClient(
            VoiceCredentials credentials,
            Consumer<IncomingVoiceFrame> voicePacketConsumer,
            Consumer<VoicePeerProfile> peerProfileConsumer,
            Consumer<Long> peerLeftConsumer,
            Consumer<List<VoicePeerProfile>> rosterConsumer,
            Consumer<String> errorConsumer
    ) {
        this.credentials = credentials;
        this.voicePacketConsumer = voicePacketConsumer;
        this.peerProfileConsumer = peerProfileConsumer;
        this.peerLeftConsumer = peerLeftConsumer;
        this.rosterConsumer = rosterConsumer;
        this.errorConsumer = errorConsumer;
    }

    public void connect() throws Exception {
        QuicClientConnection.Builder builder = QuicClientConnection.newBuilder()
                .host(credentials.host())
                .port(credentials.port())
                .applicationProtocol("just-voice/2")
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
        // actor, not the PING interval. Keep it active for this voice session.
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
        if (opcode == OP_JOINED && data.length == 1 + PEER_ID_BYTES) {
            selfPeerId = getLong(data, 1);
            joined = true;
            joinedLatch.countDown();
            LOGGER.info("Connected to Just Voice server.");
            requestRoster();
            return;
        }

        if (opcode == OP_PEER_PROFILE) {
            handlePeerProfile(data, true);
            return;
        }

        if (opcode == OP_ROSTER_BEGIN && data.length == 1) {
            rosterBuffer.clear();
            return;
        }

        if (opcode == OP_ROSTER_ENTRY) {
            handlePeerProfile(data, false);
            return;
        }

        if (opcode == OP_ROSTER_END && data.length == 1) {
            rosterConsumer.accept(List.copyOf(rosterBuffer));
            rosterBuffer.clear();
            return;
        }

        if (opcode == OP_PEER_LEFT && data.length == 1 + PEER_ID_BYTES) {
            peerLeftConsumer.accept(getLong(data, 1));
            return;
        }

        if (opcode == OP_VOICE_RELAY && data.length >= MIN_RELAY_BYTES
                && data.length <= MAX_DATAGRAM_BYTES) {
            long peerId = getLong(data, 1);
            int packetSequence = getInt(data, 9);
            int timestampMillis = getInt(data, 13);
            byte[] opus = Arrays.copyOfRange(data, 17, data.length);
            if (opus.length > 0) {
                voicePacketConsumer.accept(
                        new IncomingVoiceFrame(peerId, packetSequence, timestampMillis, opus)
                );
            }
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

    private void handlePeerProfile(byte[] data, boolean liveJoinEvent) {
        if (data.length < 1 + PEER_ID_BYTES + 1 + 3) {
            return;
        }

        int nameLength = data[9] & 0xff;
        int colorOffset = 10 + nameLength;
        if (nameLength == 0 || colorOffset + 3 != data.length) {
            return;
        }

        String name = new String(data, 10, nameLength, StandardCharsets.UTF_8);
        if (name.isBlank() || name.length() > 32
                || name.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            return;
        }

        int color = ((data[colorOffset] & 0xff) << 16)
                | ((data[colorOffset + 1] & 0xff) << 8)
                | (data[colorOffset + 2] & 0xff);
        VoicePeerProfile profile = new VoicePeerProfile(getLong(data, 1), name, color);
        if (liveJoinEvent) {
            peerProfileConsumer.accept(profile);
        } else {
            rosterBuffer.add(profile);
        }
    }

    public void requestRoster() {
        QuicClientConnection current = connection;
        if (!joined || closed || current == null || !current.isConnected()) {
            return;
        }
        try {
            current.sendDatagram(new byte[]{OP_ROSTER_REQUEST});
        } catch (RuntimeException exception) {
            LOGGER.debug("Could not request voice roster: {}", exception.getMessage());
        }
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

    public boolean isTransportConnected() {
        QuicClientConnection current = connection;
        return !closed && current != null && current.isConnected();
    }

    public boolean isJoined() {
        return joined && !closed;
    }

    public long selfPeerId() {
        return selfPeerId;
    }

    private static long getLong(byte[] source, int offset) {
        return ((long) (source[offset] & 0xff) << 56)
                | ((long) (source[offset + 1] & 0xff) << 48)
                | ((long) (source[offset + 2] & 0xff) << 40)
                | ((long) (source[offset + 3] & 0xff) << 32)
                | ((long) (source[offset + 4] & 0xff) << 24)
                | ((long) (source[offset + 5] & 0xff) << 16)
                | ((long) (source[offset + 6] & 0xff) << 8)
                | (long) (source[offset + 7] & 0xff);
    }

    private static int getInt(byte[] source, int offset) {
        return ((source[offset] & 0xff) << 24)
                | ((source[offset + 1] & 0xff) << 16)
                | ((source[offset + 2] & 0xff) << 8)
                | (source[offset + 3] & 0xff);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
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
