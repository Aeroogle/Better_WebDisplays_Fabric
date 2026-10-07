package net.montoyo.wd.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.client.ScreenCursorTracker;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.utilities.data.BlockSide;

public class InputScreen extends Screen {

    private final BlockPos screenPos;
    private final BlockSide screenSide;
    private final StringBuilder inputBuffer = new StringBuilder();

    public InputScreen(BlockPos screenPos, BlockSide screenSide) {
        super(Component.literal(""));
        this.screenPos = screenPos;
        this.screenSide = screenSide;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        Object browser = getBrowser();
        if (browser != null) {
            MCEFHelper.sendKeyPress(browser, keyCode, scanCode, modifiers);
            if (keyCode == 259 && inputBuffer.length() > 0) {
                inputBuffer.setLength(inputBuffer.length() - 1);
            }
        }
        // C2S sync: broadcast key press to other tracking players
        ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                ScreenActionPayload.encode(
                        ScreenActionPayload.keyPress(screenPos, screenSide.id, keyCode, scanCode, modifiers)));
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        Object browser = getBrowser();
        if (browser != null) {
            MCEFHelper.sendKeyRelease(browser, keyCode, scanCode, modifiers);
        }
        // C2S sync: broadcast key release to other tracking players
        ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                ScreenActionPayload.encode(
                        ScreenActionPayload.keyRelease(screenPos, screenSide.id, keyCode, scanCode, modifiers)));
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        Object browser = getBrowser();
        if (browser != null) {
            MCEFHelper.sendKeyEvent(browser, codePoint);
        }
        if (codePoint >= 32 && codePoint != 127) {
            inputBuffer.append(codePoint);
        } else if (codePoint == 8 && inputBuffer.length() > 0) {
            inputBuffer.deleteCharAt(inputBuffer.length() - 1);
        }
        // C2S sync: broadcast char input to other tracking players
        ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                ScreenActionPayload.encode(
                        ScreenActionPayload.keyType(screenPos, screenSide.id, codePoint)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
        if (cursor != null && cursor.screenData != null && cursor.screenData.browser != null) {
            long now = System.currentTimeMillis();
            int clickCount = (now - cursor.screenData.lastClickTime < 500) ? 2 : 1;
            cursor.screenData.lastClickTime = now;
            MCEFHelper.sendMouseClick(cursor.screenData.browser, cursor.pixelX, cursor.pixelY, button, false, clickCount);
            MCEFHelper.sendMouseClick(cursor.screenData.browser, cursor.pixelX, cursor.pixelY, button, true, clickCount);
            // C2S sync: broadcast mouse click to other tracking players
            ClientPlayNetworking.send(ScreenActionPayload.CHANNEL,
                    ScreenActionPayload.encode(
                            ScreenActionPayload.click(screenPos, screenSide.id, cursor.pixelX, cursor.pixelY, button, clickCount)));
        }
        return true;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        String display = inputBuffer.toString();
        if (display.length() > 40) display = "..." + display.substring(display.length() - 37);
        if (display.isEmpty()) display = " ";
        guiGraphics.drawString(this.font, "> " + display + " _", 8, 8, 0xFFFFFF, true);
        guiGraphics.drawString(this.font, "ESC to exit", 8, 20, 0x888888, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public boolean isFor(BlockPos pos, BlockSide side) {
        return screenPos.equals(pos) && screenSide == side;
    }

    public BlockPos getScreenPos() { return screenPos; }
    public BlockSide getScreenSide() { return screenSide; }

    private Object getBrowser() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (mc.level.getBlockEntity(screenPos) instanceof ScreenBlockEntity screen) {
            ScreenData data = screen.getScreen(screenSide);
            return data != null ? data.browser : null;
        }
        return null;
    }
}
