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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes incoming Opus datagrams and plays them through Java Sound.
 * A bounded queue prevents latency from growing without limit under load.
 */
public final class AudioPlayback implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int SAMPLE_RATE = 48_000;
    private static final int MAX_FRAME_SAMPLES = 5_760;
    private static final int QUEUE_CAPACITY = 50;

    private final ArrayBlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile SourceDataLine outputLine;
    private Thread worker;

    public void start() {
        worker = Thread.ofVirtual().name("just-voice-playback").start(this::playbackLoop);
    }

    public void enqueue(byte[] opusPacket) {
        if (!running.get() || opusPacket == null || opusPacket.length == 0) {
            return;
        }
        if (!packets.offer(opusPacket)) {
            packets.poll();
            packets.offer(opusPacket);
        }
    }

    private void playbackLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        SourceDataLine output = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            output = (SourceDataLine) AudioSystem.getLine(info);
            output.open(format, SAMPLE_RATE / 5);
            outputLine = output;
            output.start();

            OpusDecoder decoder = new OpusDecoder(SAMPLE_RATE, 1);
            short[] pcm = new short[MAX_FRAME_SAMPLES];
            byte[] pcmBytes = new byte[MAX_FRAME_SAMPLES * 2];

            while (running.get()) {
                byte[] packet = packets.poll(200, TimeUnit.MILLISECONDS);
                if (packet == null) {
                    continue;
                }

                try {
                    int samples = decoder.decode(
                            packet, 0, packet.length, pcm, 0, MAX_FRAME_SAMPLES, false
                    );
                    for (int i = 0; i < samples; i++) {
                        pcmBytes[i * 2] = (byte) pcm[i];
                        pcmBytes[i * 2 + 1] = (byte) (pcm[i] >>> 8);
                    }
                    output.write(pcmBytes, 0, samples * 2);
                } catch (OpusException | IllegalArgumentException exception) {
                    LOGGER.debug("Discarded invalid Opus frame: {}", exception.getMessage());
                }
            }
        } catch (LineUnavailableException | OpusException exception) {
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

    @Override
    public void close() {
        running.set(false);
        packets.clear();
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
