package dev.mentaschannel.justvoice.voice;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
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
 * 20 ms, 48 kHz, mono Opus frames. The local input meter is calculated from
 * the PCM samples before encoding.
 */
public final class MicrophoneCapture implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = 960;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    private final Consumer<byte[]> frameConsumer;
    private final Consumer<String> errorConsumer;
    private final Consumer<Float> levelConsumer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile TargetDataLine line;
    private Thread worker;

    public MicrophoneCapture(
            Consumer<byte[]> frameConsumer,
            Consumer<String> errorConsumer,
            Consumer<Float> levelConsumer
    ) {
        this.frameConsumer = frameConsumer;
        this.errorConsumer = errorConsumer;
        this.levelConsumer = levelConsumer;
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

                double squareSum = 0.0;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    int low = rawFrame[i * 2] & 0xff;
                    int high = rawFrame[i * 2 + 1];
                    short sample = (short) ((high << 8) | low);
                    pcm[i] = sample;
                    squareSum += (double) sample * sample;
                }

                float rms = (float) Math.sqrt(squareSum / FRAME_SAMPLES) / 32768.0f;
                float meterLevel = Math.min(1.0f, rms * 4.0f);
                levelConsumer.accept(meterLevel);

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
            reportFailure("Microphone device unavailable", exception);
        } catch (RuntimeException exception) {
            reportFailure("Microphone capture stopped", exception);
        } finally {
            line = null;
            levelConsumer.accept(0.0f);
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

    private void reportFailure(String reason, Exception exception) {
        if (!running.get()) {
            return;
        }
        LOGGER.warn("{}: {}", reason, exception.getMessage());
        try {
            errorConsumer.accept(reason);
        } catch (RuntimeException callbackException) {
            LOGGER.debug("Could not report microphone failure to the session manager.");
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
