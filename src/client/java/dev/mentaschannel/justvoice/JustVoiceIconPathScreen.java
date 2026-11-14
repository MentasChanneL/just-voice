package dev.mentaschannel.justvoice;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Fallback icon import screen for Linux installations without Zenity or KDialog. */
public final class JustVoiceIconPathScreen extends Screen {
    private final Screen parent;
    private final Consumer<Path> importAction;
    private EditBox pathInput;

    public JustVoiceIconPathScreen(Screen parent, Consumer<Path> importAction) {
        super(Component.translatable("screen.justvoice.icon_path.title"));
        this.parent = parent;
        this.importAction = importAction;
    }

    @Override
    protected void init() {
        int center = width / 2;
        int inputWidth = Math.min(340, width - 32);
        int left = center - inputWidth / 2;

        pathInput = new EditBox(
                font,
                left,
                88,
                inputWidth,
                20,
                Component.translatable("screen.justvoice.icon_path.label")
        );
        pathInput.setMaxLength(2048);
        pathInput.setHint(Component.translatable("screen.justvoice.icon_path.hint"));
        addRenderableWidget(pathInput);
        pathInput.setFocused(true);

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.justvoice.icon_path.import"),
                        button -> importFromPath()
                )
                .bounds(center - 104, 122, 100, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"),
                        button -> onClose()
                )
                .bounds(center + 4, 122, 100, 20)
                .build());
    }

    private void importFromPath() {
        String value = pathInput.getValue().trim();
        if (value.isEmpty()) {
            return;
        }

        try {
            String resolved = value;
            if (resolved.equals("~")) {
                resolved = System.getProperty("user.home", ".");
            } else if (resolved.startsWith("~/")) {
                resolved = Path.of(System.getProperty("user.home", "."))
                        .resolve(resolved.substring(2))
                        .toString();
            }

            Path selected = Path.of(resolved).toAbsolutePath().normalize();
            Minecraft.getInstance().gui.setScreen(parent);
            importAction.accept(selected);
        } catch (RuntimeException exception) {
            JustVoiceChat.error("screen.justvoice.icon_import_failed");
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
        graphics.centeredText(font, title, width / 2, 34, 0xFFFFFFFF);
        graphics.centeredText(
                font,
                Component.translatable("screen.justvoice.icon_path.description"),
                width / 2,
                58,
                0xFFAAAAAA
        );
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
