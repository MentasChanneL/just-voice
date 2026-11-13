package dev.mentaschannel.justvoice.voice;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * Decodes each remote speaker with an independent Opus decoder and mixes their
 * PCM frames into one output stream. Network callbacks only enqueue datagrams;
 * all decoder state is owned by the playback worker.
 */
public final class AudioPlayback implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int SAMPLE_RATE = 48_000;
    private static final int MIX_FRAME_SAMPLES = 960;
    private static final int MAX_FRAME_SAMPLES = 5_760;
    private static final int QUEUE_CAPACITY = 160;
    private static final int MAX_PEER_FRAME_BACKLOG = 4;
    private static final long PEER_DECODER_IDLE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final ArrayBlockingQueue<IncomingVoiceFrame> packets =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final LongConsumer speakerActivityConsumer;
    private final ConcurrentLinkedQueue<Long> removedPeers = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile SourceDataLine outputLine;
    private Thread worker;

    public AudioPlayback(LongConsumer speakerActivityConsumer) {
        this.speakerActivityConsumer = speakerActivityConsumer;
    }

    public void start() {
        worker = Thread.ofVirtual().name("just-voice-playback").start(this::playbackLoop);
    }

    public void enqueue(IncomingVoiceFrame frame) {
        if (!running.get() || frame == null || frame.opusPayload().length == 0) {
            return;
        }
        if (!packets.offer(frame)) {
            packets.poll();
            packets.offer(frame);
        }
    }

    public void removePeer(long peerId) {
        removedPeers.offer(peerId);
    }

    private void playbackLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        SourceDataLine output = null;
        Map<Long, PeerDecoder> decoders = new HashMap<>();
        int[] mixed = new int[MIX_FRAME_SAMPLES];
        short[] outputPcm = new short[MIX_FRAME_SAMPLES];
        byte[] outputBytes = new byte[MIX_FRAME_SAMPLES * 2];

        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            output = (SourceDataLine) AudioSystem.getLine(info);
            output.open(format, SAMPLE_RATE / 5);
            outputLine = output;
            output.start();

            while (running.get()) {
                IncomingVoiceFrame first = packets.poll(20, TimeUnit.MILLISECONDS);
                if (first != null) {
                    decode(first, decoders);
                }

                IncomingVoiceFrame next;
                while ((next = packets.poll()) != null) {
                    decode(next, decoders);
                }

                Long removedPeer;
                while ((removedPeer = removedPeers.poll()) != null) {
                    decoders.remove(removedPeer);
                }

                java.util.Arrays.fill(mixed, 0);
                boolean hasAudio = false;
                long now = System.nanoTime();
                Iterator<Map.Entry<Long, PeerDecoder>> iterator = decoders.entrySet().iterator();
                while (iterator.hasNext()) {
                    PeerDecoder state = iterator.next().getValue();
                    DecodedFrame decoded = state.frames.pollFirst();
                    if (decoded != null) {
                        hasAudio = true;
                        int count = Math.min(MIX_FRAME_SAMPLES, decoded.samples());
                        for (int i = 0; i < count; i++) {
                            mixed[i] += decoded.pcm()[i];
                        }
                    }

                    if (state.frames.isEmpty()
                            && now - state.lastPacketAtNanos > PEER_DECODER_IDLE_NANOS) {
                        iterator.remove();
                    }
                }

                if (hasAudio) {
                    for (int i = 0; i < MIX_FRAME_SAMPLES; i++) {
                        int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed[i]));
                        outputPcm[i] = (short) sample;
                        outputBytes[i * 2] = (byte) outputPcm[i];
                        outputBytes[i * 2 + 1] = (byte) (outputPcm[i] >>> 8);
                    }
                    output.write(outputBytes, 0, outputBytes.length);
                }
            }
        } catch (LineUnavailableException exception) {
            if (running.get()) {
                LOGGER.warn("Could not initialize voice playback: {}", exception.getMessage());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            if (running.get()) {
                LOGGER.warn("Voice playback stopped: {}", exception.getMessage());
            }
        } finally {
            outputLine = null;
            if (output != null) {
                output.stop();
                output.close();
            }
        }
    }

    private void decode(IncomingVoiceFrame frame, Map<Long, PeerDecoder> decoders) {
        PeerDecoder state = decoders.get(frame.peerId());
        if (state == null) {
            try {
                state = new PeerDecoder(new OpusDecoder(SAMPLE_RATE, 1));
                decoders.put(frame.peerId(), state);
            } catch (OpusException exception) {
                LOGGER.warn("Could not create Opus decoder for a voice peer: {}", exception.getMessage());
                return;
            }
        }

        if (state.hasSequence && frame.sequence() - state.lastSequence <= 0) {
            return;
        }

        byte[] opus = frame.opusPayload();
        short[] pcm = new short[MAX_FRAME_SAMPLES];
        try {
            int samples = state.decoder.decode(
                    opus, 0, opus.length, pcm, 0, MAX_FRAME_SAMPLES, false
            );
            if (samples <= 0) {
                return;
            }

            while (state.frames.size() >= MAX_PEER_FRAME_BACKLOG) {
                state.frames.removeFirst();
            }
            double squareSum = 0.0;
            for (int i = 0; i < samples; i++) {
                squareSum += (double) pcm[i] * pcm[i];
            }
            double rms = Math.sqrt(squareSum / samples);
            if (rms >= 350.0) {
                speakerActivityConsumer.accept(frame.peerId());
            }

            state.frames.addLast(new DecodedFrame(pcm, samples));
            state.lastSequence = frame.sequence();
            state.hasSequence = true;
            state.lastPacketAtNanos = System.nanoTime();
        } catch (OpusException | IllegalArgumentException exception) {
            LOGGER.debug("Discarded invalid Opus frame: {}", exception.getMessage());
        }
    }

    private static final class PeerDecoder {
        private final OpusDecoder decoder;
        private final ArrayDeque<DecodedFrame> frames = new ArrayDeque<>();
        private int lastSequence;
        private boolean hasSequence;
        private long lastPacketAtNanos = System.nanoTime();

        private PeerDecoder(OpusDecoder decoder) {
            this.decoder = decoder;
        }
    }

    private record DecodedFrame(short[] pcm, int samples) {
    }

    @Override
    public void close() {
        running.set(false);
        packets.clear();
        removedPeers.clear();
        SourceDataLine current = outputLine;
        if (current != null) {
            try {
                current.stop();
                current.close();
            } catch (RuntimeException ignored) {
                // The playback worker may be closing the line at the same time.
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
    }
}
