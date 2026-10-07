package net.montoyo.wd.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.client.gui.GuiScreenConfig;
import net.montoyo.wd.client.gui.GuiSetURL;
import net.montoyo.wd.client.gui.InputScreen;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.KeyboardBlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

public class ClientInit implements ClientModInitializer {

    private static int previousHotbarSlot = -1;
    private static boolean wasInsDown = false;
    private static long lastUrlCheckTime = 0;
    private static final long URL_CHECK_INTERVAL_MS = 1000;
    private static boolean mcefRenderingEnabled = true;
    private static boolean wasF6Down = false;
    private static int minePadTickCounter = 0;
    private static long lastScreenCreateTime = 0;
    private static final long SCREEN_CREATE_COOLDOWN_MS = 300;

    public static boolean isMCEFRenderingEnabled() {
        return mcefRenderingEnabled;
    }

    @Override
    public void onInitializeClient() {
        Log.info("WebDisplays client initializing...");

        // Register block entity renderers
        net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                WDRegistries.SCREEN_BLOCK_ENTITY, ScreenRenderer::new);

        // Schedule MCEF initialization callback (uses reflection)
        MCEFHelper.scheduleInit(success -> {
            if (success) {
                Log.info("MCEF initialized successfully for WebDisplays");
            } else {
                Log.info("MCEF not available for WebDisplays");
            }
        });

        // TEMPORARILY DISABLED: Reset GL pixel store state - testing if this causes noise
        // net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(context -> {
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        // });
        // net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        // });

        // Periodically retry browser creation for screens that were created before MCEF initialized
        // Also detect page navigation and re-inject window.open override (throttled to 1/sec)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            long now = System.currentTimeMillis();
            boolean shouldCheckUrl = (now - lastUrlCheckTime) >= URL_CHECK_INTERVAL_MS;
            if (shouldCheckUrl) lastUrlCheckTime = now;

            for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
                if (screen.retryCreateBrowsers()) {
                    Log.info("Browser retry succeeded for screen at {}", screen.getBlockPos());
                }
                // Detect page navigation and re-inject window.open override (throttled)
                if (shouldCheckUrl) {
                    for (int i = 0; i < screen.screenCount(); i++) {
                        ScreenData data = screen.getScreen(i);
                        if (data == null || data.browser == null) continue;
                        String currentUrl = MCEFHelper.getBrowserUrl(data.browser);
                        if (!currentUrl.isEmpty() && !currentUrl.equals(data.lastUrl)) {
                            data.lastUrl = currentUrl;
                            ScreenBlockEntity.ensureWindowOpenOverride(data.browser);
                            // Dynamic URL sync: when browser navigates (e.g., player clicks a
                            // video link), send the new URL to server so other players' browsers
                            // navigate to the same page (Forge S2CMessageScreenUpdate.setURL parity
                            // for in-browser navigation, not just GUI URL changes).
                            // Guard: only sync if URL differs from server-authoritative data.url
                            // (prevents feedback loop where S2C-received changes re-trigger sends).
                            if (!currentUrl.equals(data.url) &&
                                ClientPlayNetworking.canSend(ScreenActionPayload.CHANNEL)) {
                                ClientPlayNetworking.send(
                                    ScreenActionPayload.CHANNEL,
                                    ScreenActionPayload.encode(
                                        ScreenActionPayload.setUrl(
                                            screen.getBlockPos(),
                                            data.side.id,
                                            currentUrl
                                        )
                                    )
                                );
                                // Update local authoritative state to the new URL so we
                                // don't keep re-sending the same URL on subsequent polls.
                                data.url = currentUrl;
                            }
                        }
                    }
                }
            }
        });

        // === Distance-based lifecycle tick (#1): one screen per tick ===
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            ScreenBlockEntity.tickScreenTracking();
        });

        // === MinePad (#3): check hotbar every 10 ticks, close MinePad if not in hotbar ===
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            WebDisplays wd = WebDisplays.getInstance();
            if (wd.currentMinePadStack == null) return;
            if (++minePadTickCounter < 10) return;
            minePadTickCounter = 0;
            boolean inHotbar = false;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = client.player.getInventory().getItem(slot);
                if (stack == wd.currentMinePadStack) {
                    inHotbar = true;
                    break;
                }
            }
            if (!inHotbar) {
                Log.info("MinePad removed from hotbar, closing browser");
                if (wd.currentMinePadBrowser != null) {
                    MCEFHelper.closeBrowser(wd.currentMinePadBrowser);
                    wd.currentMinePadBrowser = null;
                }
                wd.currentMinePadStack = null;
            }
        });

        // === Cross-world cleanup: close all screens when client stops (exit to desktop) ===
        // NOTE: MCEFBrowser.close() calls GL11.glDeleteTextures() which requires the OpenGL
        // context (Render thread). CLIENT_STOPPING fires on the main thread which may not have
        // a current GL context, so only clear tracking lists here — browsers will be cleaned
        // up by the JVM shutdown / MCEF shutdown hook.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try {
                Log.info("Client stopping, clearing screen tracking lists...");
                ScreenBlockEntity.clearTrackingListsOnly();
            } catch (Throwable t) {
                Log.warning("Error during CLIENT_STOPPING cleanup: {}", t.getMessage());
            }
        });

        // === World exit cleanup: close all screens when leaving world (disconnect) ===
        // NOTE: DISCONNECT fires on the Netty IO thread, but MCEFBrowser.close() needs the
        // OpenGL context (Render thread). Schedule the cleanup on the Render thread via
        // Minecraft.execute() to avoid "No context is current" native crash.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> {
                try {
                    Log.info("Client disconnected from world, cleaning up all screens...");
                    ScreenBlockEntity.clearAllClientScreens();
                } catch (Throwable t) {
                    Log.warning("Error during DISCONNECT cleanup: {}", t.getMessage());
                }
            });
        });

        // === HUD overlay: show mouse mode indicator + aim hint in top-left ===
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;

            // Scale text down to 0.8x for compact HUD
            var pose = drawContext.pose();
            pose.pushPose();
            pose.scale(0.8f, 0.8f, 1.0f);

            int yOffset = 12;

            // Mouse mode indicator (top-left)
            if (ScreenCursorTracker.isCursorVisible()) {
                drawContext.drawString(mc.font, "鼠标模式: ON (Ins切换)", 12, yOffset, 0x00FF00, true);
                yOffset += 16;
                drawContext.drawString(mc.font, "左键点击屏幕/禁止破坏方块", 12, yOffset, 0xFFFF00, true);
                yOffset += 16;
            }

            // Aim hint (top-left): when crosshair targets a screen block, show Ins key prompt
            if (mc.level != null && mc.getCameraEntity() != null) {
                var hit = mc.player.pick(10.0f, 0.0f, false);
                if (hit instanceof BlockHitResult blockHit) {
                    BlockEntity be = mc.level.getBlockEntity(blockHit.getBlockPos());
                    if (be instanceof ScreenBlockEntity) {
                        drawContext.drawString(mc.font, "按 Ins 键进入鼠标模式", 12, yOffset, 0xFFFF00, true);
                    }
                }
            }

            pose.popPose();
        });

        // Track cursor position on screen planes via raycasting
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.update(client);
        });

        // Detect left-click on screen surfaces
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.handleLeftClick(client);
        });

        // Handle Shift+scroll for browser scrolling, Ctrl+scroll for zoom
        // Only intercept when Shift is held AND the hotbar slot actually changed
        // due to scroll wheel (not just pressing Shift alone)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            if (!ScreenCursorTracker.isScreenFocused()) {
                previousHotbarSlot = -1;
                return;
            }

            boolean isShift = client.player.isShiftKeyDown();
            boolean isCtrl = net.minecraft.client.gui.screens.Screen.hasControlDown();

            int currentSlot = client.player.getInventory().selected;

            if (previousHotbarSlot < 0) {
                // Initialize tracking - just record current slot, don't trigger
                previousHotbarSlot = currentSlot;
                return;
            }

            if (currentSlot != previousHotbarSlot) {
                // Slot changed - only convert to scroll if Shift or Ctrl was already held
                // This prevents false triggers when just pressing Shift
                if (isShift || isCtrl) {
                    int delta = currentSlot - previousHotbarSlot;
                    if (delta > 4) delta -= 9;
                    else if (delta < -4) delta += 9;
                    // Only handle single-step scroll wheel changes (±1)
                    // Ignore multi-step changes (likely number key presses)
                    if (Math.abs(delta) == 1) {
                        client.player.getInventory().selected = previousHotbarSlot;
                        ScreenCursorTracker.handleScroll(delta > 0 ? 1.0 : -1.0);
                        return;
                    }
                }
                // For non-scroll changes or non-shift states, just update tracking
                previousHotbarSlot = currentSlot;
            }
        });

        // Toggle cursor visibility with Insert (Ins) key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isInsDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow().getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_INSERT);
            if (isInsDown && !wasInsDown) {
                ScreenCursorTracker.toggleCursorVisible();
            }
            wasInsDown = isInsDown;
        });

        // Toggle MCEF screen rendering with F6 key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isF6Down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow().getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_F6);
            if (isF6Down && !wasF6Down) {
                mcefRenderingEnabled = !mcefRenderingEnabled;
                client.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "WebDisplays 渲染: " + (mcefRenderingEnabled ? "开启" : "关闭")),
                        true);
            }
            wasF6Down = isF6Down;
        });

        // Cancel ALL block breaking when mouse mode (cursor visible) is active
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (ScreenCursorTracker.isCursorVisible()) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Screen interaction: bare hand = create/open GUI, configurator = GUI, linker = bind
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (!(be instanceof ScreenBlockEntity screen)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
            ItemStack held = player.getItemInHand(hand);

            // 1. Configurator → always open full config GUI (width/height/URL editable)
            if (held.getItem() == WDRegistries.CONFIGURATOR) {
                Minecraft.getInstance().setScreen(
                        new GuiScreenConfig(pos, side, !screen.hasScreen(side)));
                return InteractionResult.SUCCESS;
            }

            // 2. Linker → handled by ScreenBlock.use() on server, pass here
            if (held.getItem() == WDRegistries.LINKER) {
                return InteractionResult.PASS;
            }

            // 3. Only bare hand can create/open screen
            if (!held.isEmpty()) {
                return InteractionResult.PASS;
            }

            // Throttle: prevent double-trigger from Fabric event
            long now = System.currentTimeMillis();
            if (now - lastScreenCreateTime < SCREEN_CREATE_COOLDOWN_MS) {
                return InteractionResult.SUCCESS;
            }

            ScreenData existingScreen = screen.getScreen(side);

            // Shift+right-click on existing screen → open URL-only GUI (quick URL change)
            if (player.isShiftKeyDown()) {
                if (existingScreen != null) {
                    Minecraft.getInstance().setScreen(new GuiSetURL(pos, side));
                    return InteractionResult.SUCCESS;
                }
                // No screen on this side + Shift → do nothing
                return InteractionResult.PASS;
            }

            // Normal right-click:
            // - No screen yet → open config GUI for width/height/URL (first-time creation)
            // - Screen exists → open URL-only GUI (quick URL change)
            if (existingScreen == null) {
                Minecraft.getInstance().setScreen(new GuiScreenConfig(pos, side, true));
            } else {
                Minecraft.getInstance().setScreen(new GuiSetURL(pos, side));
            }
            lastScreenCreateTime = now;
            return InteractionResult.SUCCESS;
        });

        // Open keyboard InputScreen when right-clicking keyboard blocks (empty hand only)
        // If holding Linker, let KeyboardBlockLeft.useItemOn handle the linking first
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof KeyboardBlockEntity kb) {
                if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER) return InteractionResult.PASS;
                BlockPos screenPos = kb.getLinkedPos();
                BlockSide screenSide = kb.getLinkedSide();
                if (screenPos != null && screenSide != null) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof InputScreen && ((InputScreen) mc.screen).isFor(screenPos, screenSide)) {
                        mc.setScreen(null);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: OFF"), true);
                    } else {
                        mc.setScreen(new InputScreen(screenPos, screenSide));
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: ON (ESC to exit)"), true);
                    }
                    return InteractionResult.SUCCESS;
                } else {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("webdisplays.message.notLinked"), true);
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // === Multiplayer sync: receive S2C broadcasts from server ===
        // Handles actions broadcast by the server to all tracking clients:
        //   CLICK / KEY_PRESS / KEY_RELEASE / KEY_TYPE → execute on local browser (from other players)
        //   DESTROY → force unload all browsers (kill client-side processes)
        ClientPlayNetworking.registerGlobalReceiver(ScreenActionPayload.CHANNEL, (client, clientHandler, buf, responseSender) -> {
            ScreenActionPayload payload = ScreenActionPayload.decode(buf);
            client.execute(() -> {
                if (client.level == null) return;
                BlockEntity be = client.level.getBlockEntity(payload.pos);
                if (!(be instanceof ScreenBlockEntity screen)) return;
                BlockSide side = BlockSide.fromInt(payload.sideOrdinal);

                switch (payload.action) {
                    case ScreenActionPayload.ACTION_SET_URL -> {
                        // S2C URL sync (Forge S2CMessageScreenUpdate.setURL parity).
                        // Server broadcast URL change to other tracking players.
                        // CRITICAL: only update data.lastUrl BEFORE calling setScreenURL()
                        // to prevent the local URL polling loop from re-detecting this as
                        // a new change and sending another C2S (feedback loop suppression).
                        // Do NOT update data.url here — setScreenURL() must detect
                        // urlChanged=true to actually navigate the browser.
                        ScreenData targetData = screen.getScreen(side);
                        if (targetData != null) {
                            targetData.lastUrl = payload.extraData;
                        }
                        // Use lightNavigation=true: lightweight browser.loadURL() instead
                        // of full reloadBrowser() kill+recreate. Prevents process churn
                        // during rapid page navigation sync (e.g., clicking video links).
                        screen.setScreenURL(side, payload.extraData, true);
                        // After setScreenURL(), data.url now reflects the new (possibly
                        // blacklisted) URL. Sync lastUrl to it so polling stays in sync.
                        if (targetData != null) {
                            targetData.lastUrl = targetData.url;
                        }
                    }
                    case ScreenActionPayload.ACTION_SET_RESOLUTION -> {
                        // S2C resolution sync: another player changed screen size.
                        // Apply locally (resize browser if it exists).
                        String[] parts = payload.extraData.split(",");
                        if (parts.length == 2) {
                            try {
                                int w = Integer.parseInt(parts[0]);
                                int h = Integer.parseInt(parts[1]);
                                screen.setResolution(side, new Vector2i(w, h));
                            } catch (NumberFormatException e) {
                                Log.warning("Invalid S2C resolution data: {}", payload.extraData);
                            }
                        }
                    }
                    case ScreenActionPayload.ACTION_SET_ROTATION -> {
                        // S2C rotation sync: another player changed screen rotation.
                        try {
                            int rot = Integer.parseInt(payload.extraData);
                            screen.setRotation(side, Rotation.fromInt(rot));
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid S2C rotation data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_CLICK -> {
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        String[] parts = payload.extraData.split(",");
                        if (parts.length < 4) return;
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            int button = Integer.parseInt(parts[2]);
                            int clickCount = Integer.parseInt(parts[3]);
                            MCEFHelper.sendMouseClick(data.browser, x, y, button, false, clickCount);
                            MCEFHelper.sendMouseClick(data.browser, x, y, button, true, clickCount);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid click data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_MOUSE_DOWN -> {
                        // Forge ClickControl.ControlType.DOWN parity
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        String[] parts = payload.extraData.split(",");
                        if (parts.length < 4) return;
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            int button = Integer.parseInt(parts[2]);
                            int clickCount = Integer.parseInt(parts[3]);
                            MCEFHelper.sendMouseMove(data.browser, x, y, false);
                            MCEFHelper.sendMouseClick(data.browser, x, y, button, false, clickCount);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid mouse_down data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_MOUSE_UP -> {
                        // Forge ClickControl.ControlType.UP parity
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        String[] parts = payload.extraData.split(",");
                        if (parts.length < 3) return;
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            int button = Integer.parseInt(parts[2]);
                            MCEFHelper.sendMouseClick(data.browser, x, y, button, true, 1);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid mouse_up data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_MOUSE_MOVE -> {
                        // Forge ClickControl.ControlType.MOVE parity
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        String[] parts = payload.extraData.split(",");
                        if (parts.length < 2) return;
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            MCEFHelper.sendMouseMove(data.browser, x, y, false);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid mouse_move data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_MOUSE_WHEEL -> {
                        // Mouse wheel sync (Forge has no direct equivalent; implemented for parity
                        // with Insert cursor mode scrolling)
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        String[] parts = payload.extraData.split(",");
                        if (parts.length < 4) return;
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            double delta = Double.parseDouble(parts[2]);
                            int modifiers = Integer.parseInt(parts[3]);
                            MCEFHelper.sendMouseWheel(data.browser, x, y, delta, modifiers);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid mouse_wheel data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_KEY_PRESS -> {
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        String[] parts = payload.extraData.split(",");
                        if (parts.length < 3) return;
                        try {
                            int keyCode = Integer.parseInt(parts[0]);
                            long scanCode = Long.parseLong(parts[1]);
                            int modifiers = Integer.parseInt(parts[2]);
                            MCEFHelper.sendKeyPress(data.browser, keyCode, scanCode, modifiers);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid key_press data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_KEY_RELEASE -> {
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        String[] parts = payload.extraData.split(",");
                        if (parts.length < 3) return;
                        try {
                            int keyCode = Integer.parseInt(parts[0]);
                            long scanCode = Long.parseLong(parts[1]);
                            int modifiers = Integer.parseInt(parts[2]);
                            MCEFHelper.sendKeyRelease(data.browser, keyCode, scanCode, modifiers);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid key_release data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_KEY_TYPE -> {
                        ScreenData data = screen.getScreen(side);
                        if (data == null || data.browser == null) return;
                        try {
                            char codePoint = (char) Integer.parseInt(payload.extraData);
                            MCEFHelper.sendKeyEvent(data.browser, codePoint);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid key_type data: {}", payload.extraData);
                        }
                    }
                    case ScreenActionPayload.ACTION_DESTROY -> {
                        // Forge TurnOffControl parity: side = -1 (or outside 0..5) means close ALL sides
                        // side is valid index → only close that side
                        Log.info("Received DESTROY broadcast for screen at {} sideOrdinal={}", payload.pos, payload.sideOrdinal);
                        if (payload.sideOrdinal < 0 || payload.sideOrdinal >= BlockSide.values().length) {
                            for (BlockSide bs : BlockSide.values()) {
                                screen.disableScreen(bs);
                            }
                        } else {
                            screen.disableScreen(side);
                        }
                    }
                }
            });
        });

        Log.info("WebDisplays client initialized!");
    }
}