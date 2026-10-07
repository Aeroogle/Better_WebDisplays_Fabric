package net.montoyo.wd.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class GuiScreenConfig extends Screen {
    private final BlockPos blockPos;
    private final BlockSide side;
    private final boolean isNew;
    private ScreenData screen;
    private EditBox widthInput;
    private EditBox heightInput;
    private EditBox urlInput;

    public GuiScreenConfig(BlockPos pos, BlockSide side, boolean isNew) {
        super(Component.literal("屏幕设置"));
        this.blockPos = pos;
        this.side = side;
        this.isNew = isNew;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        net.minecraft.world.level.block.entity.BlockEntity be =
                Minecraft.getInstance().level.getBlockEntity(blockPos);
        if (be instanceof ScreenBlockEntity sbe) {
            screen = sbe.getScreen(side);
        }

        if (!isNew && screen == null) {
            this.onClose();
            return;
        }

        // Width input (1-16 blocks)
        widthInput = new EditBox(this.font, cx - 100, cy - 70, 200, 20, Component.literal("宽度"));
        widthInput.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        widthInput.setMaxLength(2);
        widthInput.setValue(screen != null ? String.valueOf(screen.size.x) : "4");
        addRenderableWidget(widthInput);

        // Height input (1-16 blocks)
        heightInput = new EditBox(this.font, cx - 100, cy - 35, 200, 20, Component.literal("高度"));
        heightInput.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        heightInput.setMaxLength(2);
        heightInput.setValue(screen != null ? String.valueOf(screen.size.y) : "3");
        addRenderableWidget(heightInput);

        // URL input (always shown)
        urlInput = new EditBox(this.font, cx - 100, cy, 200, 20, Component.literal("网址URL"));
        urlInput.setMaxLength(2048);
        String currentUrl = "https://";
        if (screen != null && screen.url != null && !screen.url.isEmpty()) {
            currentUrl = screen.url;
        }
        urlInput.setValue(currentUrl);
        urlInput.setTextColor(0xFFFFFFFF);
        addRenderableWidget(urlInput);
        this.setInitialFocus(urlInput);

        // Confirm button (left)
        String confirmText = isNew ? "确认创建" : "应用修改";
        addRenderableWidget(Button.builder(
                Component.literal(confirmText),
                b -> confirm()
        ).bounds(cx - 100, cy + 35, 95, 20).build());

        // Cancel button (right) - always next to confirm
        addRenderableWidget(Button.builder(
                Component.literal("取消"),
                b -> this.onClose()
        ).bounds(cx + 5, cy + 35, 95, 20).build());

        // For existing screens: add rotation buttons below
        if (!isNew) {
            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot0"),
                    b -> setRotation(Rotation.ROT_0)
            ).bounds(cx - 95, cy + 65, 45, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot90"),
                    b -> setRotation(Rotation.ROT_90)
            ).bounds(cx - 45, cy + 65, 45, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot180"),
                    b -> setRotation(Rotation.ROT_180)
            ).bounds(cx + 5, cy + 65, 45, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot270"),
                    b -> setRotation(Rotation.ROT_270)
            ).bounds(cx + 55, cy + 65, 45, 20).build());
        }
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private void confirm() {
        // Clamp to 1-16 blocks (max 16x16)
        int bw = Math.max(1, Math.min(16, parseInt(widthInput.getValue(), 4)));
        int bh = Math.max(1, Math.min(16, parseInt(heightInput.getValue(), 3)));
        Vector2i size = new Vector2i(bw, bh);
        // Resolution multiplier: 160px per block → 16×9=2560×1440 (max)
        Vector2i res = new Vector2i(bw * 160, bh * 160);
        // Clamp to max 2560x1440 preserving aspect ratio
        int maxResX = 2560, maxResY = 1440;
        if (res.x > maxResX) {
            res.y = (int) (res.y * (float) maxResX / res.x);
            res.x = maxResX;
        }
        if (res.y > maxResY) {
            res.x = (int) (res.x * (float) maxResY / res.y);
            res.y = maxResY;
        }
        res.x = Math.max(64, res.x);
        res.y = Math.max(64, res.y);

        // Parse URL
        String urlStr = urlInput.getValue().trim();
        String finalUrl = "";
        if (!urlStr.isEmpty()) {
            try {
                finalUrl = ScreenBlockEntity.url(urlStr);
            } catch (Exception e) {
                finalUrl = "";
            }
        }

        ScreenBlockEntity sbe = getBlockEntity();
        if (sbe == null) return;

        if (isNew) {
            String owner = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getName().getString() : "unknown";
            sbe.addScreen(side, res, size, owner);
            // Set URL if provided
            if (!finalUrl.isEmpty()) {
                sbe.setScreenURL(side, finalUrl);
            }
            ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                    ScreenActionPayload.encode(ScreenActionPayload.addScreen(blockPos, side.id, bw + "," + bh)));
            // Also send URL if set (in separate packet or rely on sync)
            if (!finalUrl.isEmpty()) {
                ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                        ScreenActionPayload.encode(ScreenActionPayload.setUrl(blockPos, side.id, finalUrl)));
            }
        } else {
            // Apply size change
            sbe.setResolution(side, res);
            ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                    ScreenActionPayload.encode(ScreenActionPayload.setResolution(blockPos, side.id, res.x, res.y)));
            // Apply URL change if changed
            if (!finalUrl.isEmpty()) {
                sbe.setScreenURL(side, finalUrl);
                ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                        ScreenActionPayload.encode(ScreenActionPayload.setUrl(blockPos, side.id, finalUrl)));
            }
        }
        this.onClose();
    }

    private void setRotation(Rotation rot) {
        ScreenBlockEntity sbe = getBlockEntity();
        if (sbe != null) {
            sbe.setRotation(side, rot);
            ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                    ScreenActionPayload.encode(ScreenActionPayload.setRotation(blockPos, side.id, rot.id)));
        }
        this.onClose();
    }

    private ScreenBlockEntity getBlockEntity() {
        net.minecraft.world.level.block.entity.BlockEntity be =
                Minecraft.getInstance().level.getBlockEntity(blockPos);
        return (be instanceof ScreenBlockEntity sbe) ? sbe : null;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Draw widgets first (inputs, buttons)
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Draw text on top at the same layer as the GUI
        int cx = this.width / 2;
        int cy = this.height / 2;

        guiGraphics.drawCenteredString(this.font, this.title, cx, cy - 95, 0xFFFFFF);
        guiGraphics.drawString(this.font, "宽度 (1-16方块):", cx - 100, cy - 82, 0xCCCCCC);
        guiGraphics.drawString(this.font, "高度 (1-16方块):", cx - 100, cy - 47, 0xCCCCCC);
        guiGraphics.drawString(this.font, "网址URL (https://):", cx - 100, cy - 12, 0xCCCCCC);

        // Resolution preview
        int bw = Math.max(1, Math.min(16, parseInt(widthInput.getValue(), 4)));
        int bh = Math.max(1, Math.min(16, parseInt(heightInput.getValue(), 3)));
        int rx = Math.min(2560, bw * 160);
        int ry = Math.min(1440, bh * 160);
        guiGraphics.drawCenteredString(this.font,
                "预览分辨率: " + rx + "x" + ry + " (最大2560x1440)",
                cx, cy + 100, 0x888888);

        if (!isNew && screen != null) {
            guiGraphics.drawCenteredString(this.font,
                    "所有者: " + (screen.owner != null ? screen.owner : "N/A"),
                    cx, cy - 110, 0xAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
