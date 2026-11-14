package dev.mentaschannel.justvoice;

import dev.mentaschannel.justvoice.voice.VoicePeerProfile;
import dev.mentaschannel.justvoice.voice.VoiceSessionManager;
import dev.mentaschannel.justvoice.voice.VoiceSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Small built-in settings screen exposed through Mod Menu. */
public final class JustVoiceConfigScreen extends Screen {
    private final Screen parent;
    private final VoiceSettings settings;
    private final VoiceSessionManager session;

    private List<String> inputDevices;
    private List<String> outputDevices;
    private List<VoicePeerProfile> remotePeers;
    private int inputDeviceIndex;
    private int outputDeviceIndex;
    private int selectedPeerIndex;

    private Button inputDeviceButton;
    private Button outputDeviceButton;
    private Button inputGainButton;
    private Button masterVolumeButton;
    private Button peerButton;
    private Button peerVolumeButton;

    public JustVoiceConfigScreen(Screen parent) {
        super(Component.translatable("screen.justvoice.title"));
        this.parent = parent;
        this.settings = JustVoiceClient.getVoiceSettings();
        this.session = JustVoiceClient.getActiveSessionManager();
    }

    @Override
    protected void init() {
        inputDevices = VoiceSettings.availableDevices();
        outputDevices = VoiceSettings.availableDevices();
        remotePeers = session == null ? List.of() : session.getRemoteRoomMembers();
        inputDeviceIndex = selectedIndex(inputDevices, settings.inputDevice());
        outputDeviceIndex = selectedIndex(outputDevices, settings.outputDevice());
        selectedPeerIndex = Math.max(0, Math.min(selectedPeerIndex, Math.max(0, remotePeers.size() - 1)));

        int center = width / 2;
        int buttonWidth = Math.min(340, width - 32);
        int left = center - buttonWidth / 2;
        int y = 62;

        inputDeviceButton = addRenderableWidget(Button.builder(inputDeviceLabel(), button -> cycleInputDevice())
                .bounds(left, y, buttonWidth, 20).build());
        y += 27;

        outputDeviceButton = addRenderableWidget(Button.builder(outputDeviceLabel(), button -> cycleOutputDevice())
                .bounds(left, y, buttonWidth, 20).build());
        y += 35;

        addRenderableWidget(Button.builder(Component.literal("−"), button -> changeInputGain(-0.1f))
                .bounds(left, y, 38, 20).build());
        inputGainButton = addRenderableWidget(Button.builder(inputGainLabel(), button -> { })
                .bounds(left + 42, y, buttonWidth - 84, 20).build());
        inputGainButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), button -> changeInputGain(0.1f))
                .bounds(left + buttonWidth - 38, y, 38, 20).build());
        y += 27;

        addRenderableWidget(Button.builder(Component.literal("−"), button -> changeMasterVolume(-0.1f))
                .bounds(left, y, 38, 20).build());
        masterVolumeButton = addRenderableWidget(Button.builder(masterVolumeLabel(), button -> { })
                .bounds(left + 42, y, buttonWidth - 84, 20).build());
        masterVolumeButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), button -> changeMasterVolume(0.1f))
                .bounds(left + buttonWidth - 38, y, 38, 20).build());
        y += 35;

        addRenderableWidget(Button.builder(Component.literal("‹"), button -> cyclePeer(-1))
                .bounds(left, y, 38, 20).build());
        peerButton = addRenderableWidget(Button.builder(selectedPeerLabel(), button -> { })
                .bounds(left + 42, y, buttonWidth - 122, 20).build());
        peerButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("›"), button -> cyclePeer(1))
                .bounds(left + buttonWidth - 76, y, 38, 20).build());
        addRenderableWidget(Button.builder(Component.literal("−"), button -> changePeerVolume(-0.1f))
                .bounds(left + buttonWidth - 34, y, 34, 20).build());
        y += 25;

        peerVolumeButton = addRenderableWidget(Button.builder(peerVolumeLabel(), button -> { })
                .bounds(left, y, buttonWidth, 20).build());
        peerVolumeButton.active = false;
        y += 32;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(center - 100, Math.min(height - 30, y + 5), 200, 20).build());

        refreshLabels();
    }

    private static int selectedIndex(List<String> devices, String current) {
        int index = devices.indexOf(current == null ? "" : current);
        return index < 0 ? 0 : index;
    }

    private void cycleInputDevice() {
        inputDeviceIndex = (inputDeviceIndex + 1) % inputDevices.size();
        settings.setInputDevice(inputDevices.get(inputDeviceIndex));
        refreshLabels();
    }

    private void cycleOutputDevice() {
        outputDeviceIndex = (outputDeviceIndex + 1) % outputDevices.size();
        settings.setOutputDevice(outputDevices.get(outputDeviceIndex));
        if (session != null) {
            session.restartPlayback();
        }
        refreshLabels();
    }

    private void changeInputGain(float delta) {
        settings.setInputGain(settings.inputGain() + delta);
        refreshLabels();
    }

    private void changeMasterVolume(float delta) {
        settings.setMasterVolume(settings.masterVolume() + delta);
        refreshLabels();
    }

    private void cyclePeer(int direction) {
        if (remotePeers.isEmpty()) {
            return;
        }
        selectedPeerIndex = Math.floorMod(selectedPeerIndex + direction, remotePeers.size());
        refreshLabels();
    }

    private void changePeerVolume(float delta) {
        VoicePeerProfile peer = selectedPeer();
        if (peer == null || session == null) {
            return;
        }
        session.setPlayerVolume(peer.name(), session.getPlayerVolume(peer.name()) + delta);
        refreshLabels();
    }

    private VoicePeerProfile selectedPeer() {
        if (remotePeers.isEmpty() || selectedPeerIndex >= remotePeers.size()) {
            return null;
        }
        return remotePeers.get(selectedPeerIndex);
    }

    private Component inputDeviceLabel() {
        return Component.translatable(
                "screen.justvoice.input_device",
                VoiceSettings.displayDevice(settings.inputDevice())
        );
    }

    private Component outputDeviceLabel() {
        return Component.translatable(
                "screen.justvoice.output_device",
                VoiceSettings.displayDevice(settings.outputDevice())
        );
    }

    private Component inputGainLabel() {
        return Component.translatable("screen.justvoice.input_gain", Math.round(settings.inputGain() * 100));
    }

    private Component masterVolumeLabel() {
        return Component.translatable("screen.justvoice.master_volume", Math.round(settings.masterVolume() * 100));
    }

    private Component selectedPeerLabel() {
        VoicePeerProfile peer = selectedPeer();
        return peer == null
                ? Component.translatable("screen.justvoice.no_peers")
                : Component.translatable("screen.justvoice.player", peer.name());
    }

    private Component peerVolumeLabel() {
        VoicePeerProfile peer = selectedPeer();
        if (peer == null || session == null) {
            return Component.translatable("screen.justvoice.player_volume", 100);
        }
        return Component.translatable(
                "screen.justvoice.player_volume",
                Math.round(session.getPlayerVolume(peer.name()) * 100)
        );
    }

    private void refreshLabels() {
        if (inputDeviceButton != null) {
            inputDeviceButton.setMessage(inputDeviceLabel());
            outputDeviceButton.setMessage(outputDeviceLabel());
            inputGainButton.setMessage(inputGainLabel());
            masterVolumeButton.setMessage(masterVolumeLabel());
            peerButton.setMessage(selectedPeerLabel());
            peerVolumeButton.setMessage(peerVolumeLabel());
            boolean hasPeer = selectedPeer() != null;
            peerButton.active = hasPeer;
            peerVolumeButton.active = hasPeer;
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 24, 0xFFFFFFFF);
        graphics.centeredText(
                font,
                Component.translatable("screen.justvoice.description"),
                width / 2,
                42,
                0xFFAAAAAA
        );
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
