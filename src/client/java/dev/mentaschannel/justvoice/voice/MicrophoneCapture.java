package dev.mentaschannel.justvoice.voice;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALCCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opens the selected OpenAL capture endpoint only while push-to-talk is held.
 * Device identifiers are provided by OpenAL Capture enumeration.
 */
public final class MicrophoneCapture implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = 960;
    private static final int CAPTURE_BUFFER_SAMPLES = FRAME_SAMPLES * 12;

    private final Consumer<byte[]> frameConsumer;
    private final Consumer<String> errorConsumer;
    private final Consumer<Float> levelConsumer;
    private final VoiceSettings settings;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread worker;

    public MicrophoneCapture(
            Consumer<byte[]> frameConsumer,
            Consumer<String> errorConsumer,
            Consumer<Float> levelConsumer,
            VoiceSettings settings
    ) {
        this.frameConsumer = frameConsumer;
        this.errorConsumer = errorConsumer;
        this.levelConsumer = levelConsumer;
        this.settings = settings;
    }

    public void start() {
        worker = Thread.ofPlatform().daemon().name("just-voice-microphone").start(this::captureLoop);
    }

    private void captureLoop() {
        long device = 0L;
        ALCCapabilities alcCaps = null;
        try {
            String selectedDevice = settings.inputDevice();
            device = ALC11.alcCaptureOpenDevice(
                    selectedDevice == null || selectedDevice.isBlank() ? null : selectedDevice,
                    SAMPLE_RATE,
                    AL10.AL_FORMAT_MONO16,
                    CAPTURE_BUFFER_SAMPLES
            );
            if (device == 0L && selectedDevice != null && !selectedDevice.isBlank()) {
                LOGGER.warn("Selected OpenAL capture device is unavailable; using system default: {}", selectedDevice);
                settings.setInputDevice("");
                device = ALC11.alcCaptureOpenDevice(
                        (String) null,
                        SAMPLE_RATE,
                        AL10.AL_FORMAT_MONO16,
                        CAPTURE_BUFFER_SAMPLES
                );
            }
            if (device == 0L) {
                throw new IllegalStateException("Could not open an OpenAL capture device");
            }

            alcCaps = ALC.createCapabilities(device);
            if (!alcCaps.ALC_EXT_CAPTURE) {
                throw new IllegalStateException("OpenAL implementation does not support microphone capture");
            }
            if (!running.get()) {
                return;
            }

            ALC11.alcCaptureStart(device);
            OpusEncoder encoder = new OpusEncoder(
                    SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP
            );
            short[] captured = new short[FRAME_SAMPLES];
            short[] pcm = new short[FRAME_SAMPLES];
            byte[] encoded = new byte[1024];

            while (running.get()) {
                int available = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
                if (available < FRAME_SAMPLES) {
                    Thread.sleep(2L);
                    continue;
                }

                ALC11.alcCaptureSamples(device, captured, FRAME_SAMPLES);
                float gain = settings.inputGain();
                double squareSum = 0.0;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    int amplified = Math.round(captured[i] * gain);
                    short sample = (short) Math.max(
                            Short.MIN_VALUE,
                            Math.min(Short.MAX_VALUE, amplified)
                    );
                    pcm[i] = sample;
                    squareSum += (double) sample * sample;
                }

                float rms = (float) Math.sqrt(squareSum / FRAME_SAMPLES) / 32768.0f;
                levelConsumer.accept(Math.min(1.0f, rms * 4.0f));

                int length = encoder.encode(
                        pcm, 0, FRAME_SAMPLES, encoded, 0, encoded.length
                );
                if (length > 0 && running.get()) {
                    byte[] frame = new byte[length];
                    System.arraycopy(encoded, 0, frame, 0, length);
                    frameConsumer.accept(frame);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | OpusException exception) {
            reportFailure("OpenAL microphone capture failed", exception);
        } finally {
            if (device != 0L) {
                try {
                    ALC11.alcCaptureStop(device);
                } catch (RuntimeException ignored) {
                    // Capture may already have been stopped by the device.
                }
                ALC11.alcCaptureCloseDevice(device);
            }
            if (alcCaps != null) {
                ALC.setCapabilities(null);
            }
            levelConsumer.accept(0.0f);
        }
    }

    private void reportFailure(String reason, Exception exception) {
        if (!running.get()) {
            return;
        }
        LOGGER.warn("{}: {}", reason, exception.getMessage());
        // Do not call back into the synchronized session manager from this
        // worker: device switching waits for this native capture thread to stop.
        ForkJoinPool.commonPool().execute(() -> {
            try {
                errorConsumer.accept(reason);
            } catch (RuntimeException callbackException) {
                LOGGER.debug("Could not report microphone failure to the session manager.");
            }
        });
    }

    @Override
    public void close() {
        running.set(false);
        Thread currentWorker = worker;
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            currentWorker.interrupt();
            try {
                currentWorker.join(2_000L);
                if (currentWorker.isAlive()) {
                    LOGGER.warn("Microphone capture worker did not stop within 2 seconds.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
