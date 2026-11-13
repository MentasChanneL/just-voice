package dev.mentaschannel.justvoice.voice;

import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opens the microphone only while push-to-talk is held. Audio is encoded as
 * 20 ms, 48 kHz, mono Opus frames before it is handed to the network client.
 */
public final class MicrophoneCapture implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = 960;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    private final Consumer<byte[]> frameConsumer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile TargetDataLine line;
    private Thread worker;

    public MicrophoneCapture(Consumer<byte[]> frameConsumer) {
        this.frameConsumer = frameConsumer;
    }

    public void start() {
        worker = Thread.ofVirtual().name("just-voice-microphone").start(this::captureLoop);
    }

    private void captureLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        TargetDataLine openedLine = null;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            openedLine = (TargetDataLine) AudioSystem.getLine(info);
            openedLine.open(format, FRAME_BYTES * 8);
            line = openedLine;
            if (!running.get()) {
                return;
            }
            openedLine.start();

            OpusEncoder encoder = new OpusEncoder(
                    SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP
            );
            byte[] rawFrame = new byte[FRAME_BYTES];
            short[] pcm = new short[FRAME_SAMPLES];
            byte[] encoded = new byte[1024];

            while (running.get()) {
                int offset = 0;
                while (running.get() && offset < rawFrame.length) {
                    int count = openedLine.read(rawFrame, offset, rawFrame.length - offset);
                    if (count < 0) {
                        return;
                    }
                    if (count == 0) {
                        continue;
                    }
                    offset += count;
                }
                if (!running.get() || offset != rawFrame.length) {
                    break;
                }

                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    int low = rawFrame[i * 2] & 0xff;
                    int high = rawFrame[i * 2 + 1];
                    pcm[i] = (short) ((high << 8) | low);
                }

                int length = encoder.encode(
                        pcm, 0, FRAME_SAMPLES, encoded, 0, encoded.length
                );
                if (length > 0 && running.get()) {
                    byte[] frame = new byte[length];
                    System.arraycopy(encoded, 0, frame, 0, length);
                    frameConsumer.accept(frame);
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException | OpusException exception) {
            if (running.get()) {
                LOGGER.warn("Could not capture or encode microphone audio: {}", exception.getMessage());
            }
        } catch (RuntimeException exception) {
            if (running.get()) {
                LOGGER.warn("Microphone capture stopped: {}", exception.getMessage());
            }
        } finally {
            line = null;
            if (openedLine != null) {
                try {
                    openedLine.stop();
                    openedLine.close();
                } catch (RuntimeException ignored) {
                    // The UI thread may have closed the line to unblock read().
                }
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        TargetDataLine current = line;
        if (current != null) {
            try {
                current.stop();
                current.close();
            } catch (RuntimeException ignored) {
                // The capture worker may be closing the line at the same time.
            }
        }
    }
}
