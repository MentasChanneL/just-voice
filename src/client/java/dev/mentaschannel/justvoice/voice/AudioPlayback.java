package dev.mentaschannel.justvoice.voice;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/**
 * Decodes Opus voices, mixes PCM and plays them through an OpenAL output device.
 * Device specifiers are the same identifiers exposed by Minecraft's sound settings.
 */
public final class AudioPlayback implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("justvoice");
    private static final int SAMPLE_RATE = 48_000;
    private static final int MIX_FRAME_SAMPLES = 960;
    private static final int MAX_FRAME_SAMPLES = 5_760;
    private static final int QUEUE_CAPACITY = 160;
    private static final int MAX_PEER_FRAME_BACKLOG = 4;
    private static final int OUTPUT_BUFFER_COUNT = 6;
    private static final long PEER_DECODER_IDLE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final ArrayBlockingQueue<IncomingVoiceFrame> packets = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final LongConsumer speakerActivityConsumer;
    private final LongFunction<String> peerNameResolver;
    private final VoiceSettings settings;
    private final ConcurrentLinkedQueue<Long> removedPeers = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread worker;

    public AudioPlayback(
            LongConsumer speakerActivityConsumer,
            LongFunction<String> peerNameResolver,
            VoiceSettings settings
    ) {
        this.speakerActivityConsumer = speakerActivityConsumer;
        this.peerNameResolver = peerNameResolver;
        this.settings = settings;
    }

    public void start() {
        worker = Thread.ofPlatform().daemon().name("just-voice-playback").start(this::playbackLoop);
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
        long device = 0L;
        long context = 0L;
        ALCCapabilities alcCaps = null;
        ALCapabilities alCaps = null;
        int source = 0;
        int[] buffers = new int[OUTPUT_BUFFER_COUNT];
        Map<Long, PeerDecoder> decoders = new HashMap<>();
        int[] mixed = new int[MIX_FRAME_SAMPLES];
        short[] outputSamples = new short[MIX_FRAME_SAMPLES];
        ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();

        try {
            String selectedDevice = settings.outputDevice();
            device = ALC10.alcOpenDevice(selectedDevice == null || selectedDevice.isBlank()
                    ? (CharSequence) null : selectedDevice);
            if (device == 0L && selectedDevice != null && !selectedDevice.isBlank()) {
                LOGGER.warn("Selected OpenAL output device is unavailable; using system default: {}", selectedDevice);
                settings.setOutputDevice("");
                device = ALC10.alcOpenDevice((CharSequence) null);
            }
            if (device == 0L) {
                throw new IllegalStateException("Could not open an OpenAL output device");
            }

            alcCaps = ALC.createCapabilities(device);
            if (!alcCaps.ALC_EXT_thread_local_context) {
                throw new IllegalStateException("OpenAL implementation does not support thread-local contexts");
            }

            context = ALC10.alcCreateContext(device, (int[]) null);
            if (context == 0L || !EXTThreadLocalContext.alcSetThreadContext(context)) {
                throw new IllegalStateException("Could not create a thread-local OpenAL context");
            }
            alCaps = AL.createCapabilities(alcCaps);
            source = AL10.alGenSources();
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = AL10.alGenBuffers();
                freeBuffers.addLast(buffers[i]);
            }

            boolean voiceWasEnabled = settings.isEnabled();
            while (running.get()) {
                IncomingVoiceFrame first = packets.poll(20, TimeUnit.MILLISECONDS);

                if (!settings.isEnabled()) {
                    // Local OFF is not a room disconnect: discard incoming audio
                    // and silence any already queued samples without closing QUIC.
                    packets.clear();
                    removedPeers.clear();
                    decoders.clear();

                    if (voiceWasEnabled) {
                        AL10.alSourceStop(source);
                        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                        while (queued-- > 0) {
                            freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source));
                        }
                        voiceWasEnabled = false;
                    }
                    continue;
                }
                voiceWasEnabled = true;

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

                int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                while (processed-- > 0) {
                    freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source));
                }

                java.util.Arrays.fill(mixed, 0);
                boolean hasAudio = false;
                long now = System.nanoTime();
                Iterator<Map.Entry<Long, PeerDecoder>> iterator = decoders.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Long, PeerDecoder> entry = iterator.next();
                    PeerDecoder state = entry.getValue();
                    DecodedFrame decoded = state.frames.pollFirst();
                    if (decoded != null) {
                        String peerName = peerNameResolver.apply(entry.getKey());
                        double volume = settings.isPlayerMuted(peerName)
                                ? 0.0
                                : settings.playerVolume(peerName) * settings.masterVolume();
                        if (volume > 0.0) {
                            hasAudio = true;
                            int count = Math.min(MIX_FRAME_SAMPLES, decoded.samples());
                            for (int i = 0; i < count; i++) {
                                mixed[i] += (int) Math.round(decoded.pcm()[i] * volume);
                            }
                        }
                    }

                    if (state.frames.isEmpty()
                            && now - state.lastPacketAtNanos > PEER_DECODER_IDLE_NANOS) {
                        iterator.remove();
                    }
                }

                if (hasAudio && !freeBuffers.isEmpty()) {
                    for (int i = 0; i < MIX_FRAME_SAMPLES; i++) {
                        outputSamples[i] = (short) Math.max(
                                Short.MIN_VALUE,
                                Math.min(Short.MAX_VALUE, mixed[i])
                        );
                    }

                    int buffer = freeBuffers.removeFirst();
                    ShortBuffer pcm = MemoryUtil.memAllocShort(outputSamples.length);
                    try {
                        pcm.put(outputSamples).flip();
                        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcm, SAMPLE_RATE);
                        AL10.alSourceQueueBuffers(source, buffer);
                    } finally {
                        MemoryUtil.memFree(pcm);
                    }

                    if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                        AL10.alSourcePlay(source);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            if (running.get()) {
                LOGGER.warn("Could not initialize or run OpenAL voice playback: {}", exception.getMessage());
            }
        } finally {
            if (alCaps != null) {
                if (source != 0) {
                    try {
                        AL10.alSourceStop(source);
                        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                        while (queued-- > 0) {
                            AL10.alSourceUnqueueBuffers(source);
                        }
                        AL10.alDeleteSources(source);
                    } catch (RuntimeException ignored) {
                        // The device may already have been disconnected.
                    }
                }
                for (int buffer : buffers) {
                    if (buffer != 0) {
                        try {
                            AL10.alDeleteBuffers(buffer);
                        } catch (RuntimeException ignored) {
                            // The device may already have been disconnected.
                        }
                    }
                }
            }
            // Clear the native thread-local context before destroying it. OpenAL
            // contexts are bound to OS threads, so this worker must remain a
            // dedicated platform thread rather than a migratable virtual thread.
            if (alcCaps != null && alcCaps.ALC_EXT_thread_local_context) {
                EXTThreadLocalContext.alcSetThreadContext(0L);
            }
            AL.setCurrentThread(null);
            if (context != 0L) {
                ALC10.alcDestroyContext(context);
            }
            if (device != 0L) {
                ALC10.alcCloseDevice(device);
            }
            if (alcCaps != null) {
                ALC.setCapabilities(null);
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
            int samples = state.decoder.decode(opus, 0, opus.length, pcm, 0, MAX_FRAME_SAMPLES, false);
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
        Thread currentWorker = worker;
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            currentWorker.interrupt();
            try {
                currentWorker.join(2_000L);
                if (currentWorker.isAlive()) {
                    LOGGER.warn("Voice playback worker did not stop within 2 seconds.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
