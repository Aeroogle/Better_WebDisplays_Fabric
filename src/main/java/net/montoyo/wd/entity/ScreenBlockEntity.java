package net.montoyo.wd.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;
import org.joml.Vector3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ScreenBlockEntity extends BlockEntity {

    private static final List<ScreenBlockEntity> clientScreens = new ArrayList<>();
    private static volatile boolean clientScreensDirty = true;
    private static List<ScreenBlockEntity> clientScreensSnapshot = List.of();

    public static List<ScreenBlockEntity> getClientScreens() {
        if (clientScreensDirty) {
            synchronized (clientScreens) {
                clientScreensSnapshot = new ArrayList<>(clientScreens);
                clientScreensDirty = false;
            }
        }
        return clientScreensSnapshot;
    }

    /**
     * Destroy ALL client-side browsers and clear tracking lists.
     * Called on world exit (DISCONNECT) and client shutdown (CLIENT_STOPPING).
     * Uses 3-step kill for each browser: JS mute → about:blank → browser.close.
     */
    public static void clearAllClientScreens() {
        List<ScreenBlockEntity> snapshot;
        synchronized (clientScreens) {
            snapshot = new ArrayList<>(clientScreens);
        }
        int count = 0;
        for (ScreenBlockEntity screen : snapshot) {
            for (ScreenData sd : screen.screens) {
                if (sd.browser != null) {
                    try {
                        sd.unload();
                        count++;
                    } catch (Throwable t) {
                        Log.warning("Error closing browser during cleanup: {}", t.getMessage());
                        sd.browser = null;
                    }
                }
            }
            screen.screens.clear();
            screen.loaded = false;
        }
        synchronized (clientScreens) {
            clientScreens.clear();
            clientScreensDirty = true;
        }
        synchronized (trackedScreens) {
            trackedScreens.clear();
        }
        Log.info("clearAllClientScreens: destroyed {} browser(s) and cleared {} BEs", count, snapshot.size());
    }

    /**
     * Only clear the tracking lists without closing browsers.
     * Used in CLIENT_STOPPING where the OpenGL context may not be available
     * (MCEFBrowser.close() calls glDeleteTextures which needs Render thread).
     * Browser processes will be cleaned up by the JVM shutdown hook.
     */
    public static void clearTrackingListsOnly() {
        synchronized (clientScreens) {
            clientScreens.clear();
            clientScreensDirty = true;
        }
        synchronized (trackedScreens) {
            trackedScreens.clear();
        }
        Log.info("clearTrackingListsOnly: cleared tracking lists (browsers left for JVM shutdown)");
    }

    /** Only screens in this tracked list are checked for distance-based lifecycle. */
    private static final List<ScreenBlockEntity> trackedScreens = new ArrayList<>();
    private static int trackedIndex = 0;

    public static List<ScreenBlockEntity> getTrackedScreens() {
        return trackedScreens;
    }

    public static void tickScreenTracking() {
        if (trackedScreens.isEmpty()) return;
        if (trackedIndex >= trackedScreens.size()) trackedIndex = 0;
        ScreenBlockEntity screen = trackedScreens.get(trackedIndex);
        if (screen.level == null || !screen.level.isClientSide) {
            trackedScreens.remove(trackedIndex);
            return;
        }
        screen.checkDistanceLifecycle();
        trackedIndex++;
    }

    private final ArrayList<ScreenData> screens = new ArrayList<>();
    private boolean loaded = false;
    private boolean destroyed = false;
    private AABB renderBB;
    private float ytVolume = 1.0f;
    public boolean doTurnOnAnim = false;
    public long turnOnStartTime = 0L;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(WDRegistries.SCREEN_BLOCK_ENTITY, pos, state);
    }

    // === Network Sync ===

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (level.isClientSide) {
            synchronized (clientScreens) {
                if (!clientScreens.contains(this)) {
                    clientScreens.add(this);
                    clientScreensDirty = true;
                }
            }
            synchronized (trackedScreens) {
                if (!trackedScreens.contains(this)) trackedScreens.add(this);
            }
        }
    }

    @Override
    public void setRemoved() {
        // Fallback: LevelChunk.setBlockState() may remove the block entity (calling
        // setRemoved()) BEFORE onRemove() runs, so onRemove()'s getBlockEntity()
        // returns null and onDestroy() is never invoked. This ensures browsers
        // are still killed on the client. The 'destroyed' flag makes it idempotent.
        // Server-side onDestroy() is handled by playerWillDestroy()/onRemove().
        if (level != null && level.isClientSide && !destroyed) {
            Log.warning("setRemoved() before onDestroy() — forcing browser cleanup at {}", worldPosition);
            onDestroy();
        }
        // Always ensure tracking lists are clean (harmless if already removed by onDestroy)
        if (level != null && level.isClientSide) {
            synchronized (clientScreens) {
                clientScreens.remove(this);
                clientScreensDirty = true;
            }
            synchronized (trackedScreens) {
                trackedScreens.remove(this);
            }
        }
        super.setRemoved();
    }

    // === Screen Management ===

    public int screenCount() { return screens.size(); }

    public ScreenData getScreen(int index) {
        return (index >= 0 && index < screens.size()) ? screens.get(index) : null;
    }

    public ScreenData getScreen(BlockSide side) {
        for (ScreenData sc : screens) if (sc.side == side) return sc;
        return null;
    }

    public boolean hasScreen(BlockSide side) { return getScreen(side) != null; }

    public void addScreen(BlockSide side, Vector2i resolution, Vector2i size, String owner) {
        // Single screen constraint: only one screen per block entity
        if (!screens.isEmpty()) {
            Log.warning("addScreen rejected: screen already exists at {}", worldPosition);
            return;
        }
        if (getScreen(side) != null) return;
        // Clamp resolution to max 2560x1440, preserving aspect ratio (same as setResolution)
        resolution.x = Math.max(64, resolution.x);
        resolution.y = Math.max(64, resolution.y);
        int maxResX = 1920, maxResY = 1080;
        if (resolution.x > maxResX) {
            resolution.y = (int) (resolution.y * (float) maxResX / resolution.x);
            resolution.x = maxResX;
        }
        if (resolution.y > maxResY) {
            resolution.x = (int) (resolution.x * (float) maxResY / resolution.y);
            resolution.y = maxResY;
        }
        resolution.x = Math.max(64, resolution.x);
        resolution.y = Math.max(64, resolution.y);
        screens.add(new ScreenData(side, resolution, size, owner));
        updateAABB();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void removeScreen(BlockSide side) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.unload();
        screens.remove(screen);
        updateAABB();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // === Screen Configuration ===

    public void setScreenURL(BlockSide side, String url) {
        setScreenURL(side, url, false);
    }

    /**
     * Set screen URL with optional light-navigation mode.
     *
     * @param side the screen side
     * @param url the new URL
     * @param lightNavigation if true, use browser.loadURL() instead of full reloadBrowser()
     *                        (used for dynamic page-internal navigation sync; avoids kill+recreate process churn)
     */
    public void setScreenURL(BlockSide side, String url, boolean lightNavigation) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        String newUrl = WebDisplays.applyBlacklist(url);
        boolean urlChanged = !newUrl.equals(screen.url);
        screen.url = newUrl;
        if (level != null && level.isClientSide && urlChanged) {
            if (lightNavigation && screen.browser != null) {
                // Lightweight navigation: just navigate the existing browser to the new URL
                // (no process destruction/recreation). Used for in-browser navigation sync
                // (e.g., clicking a video link on one player's browser syncs navigation to others).
                MCEFHelper.loadBrowserUrl(screen.browser, newUrl);
            } else {
                // Heavy navigation: kill old browser process and create a new one.
                // Used for manual URL changes via GUI (GuiSetURL / GuiScreenConfig).
                reloadBrowser(side);
            }
        }
        setChanged();
        // Server-side: URL sync to other players is handled by ServerNetHandler
        // via ACTION_SET_URL broadcast (Forge S2CMessageScreenUpdate.setURL parity).
    }

    public void setResolution(BlockSide side, Vector2i res) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        res.x = Math.max(64, res.x);
        res.y = Math.max(64, res.y);
        // Clamp to max 2560x1440, preserving aspect ratio
        int maxResX = 2560;
        int maxResY = 1440;
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
        screen.resolution.set(res.x, res.y);
        // 160px per block: 16×9=2560×1440 (matches multiplier in GuiScreenConfig and addScreen)
        screen.size.x = Math.max(1, (int) Math.ceil(res.x / 160.0f));
        screen.size.y = Math.max(1, (int) Math.ceil(res.y / 160.0f));
        if (level != null && level.isClientSide && screen.browser != null) {
            MCEFHelper.resizeBrowser(screen.browser, res.x, res.y);
        }
        setChanged();
        // Server-side: resolution sync to other players is handled by ServerNetHandler
        // via ACTION_SET_RESOLUTION broadcast. No sendBlockUpdated() here — that would
        // trigger a full BlockEntity NBT resend via load(), causing a race condition
        // where the client's locally-applied changes get overwritten.
        updateAABB();
    }

    public void setRotation(BlockSide side, Rotation rot) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.rotation = rot;
        setChanged();
        // Server-side: rotation sync to other players is handled by ServerNetHandler
        // via ACTION_SET_ROTATION broadcast.
    }

    public void setAutoVolume(BlockSide side, boolean av) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.autoVolume = av;
        setChanged();
    }

    public void setOwner(BlockSide side, String owner) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.owner = owner;
        setChanged();
    }

    // === Mouse Interaction ===

    public void click(Player player, BlockHitResult hit) {
        BlockSide side = BlockSide.fromDirection(hit.getDirection());
        clickAt(player, side, hit);
    }

    public void hitToScreenCoords(ScreenData screen, double localX, double localY, double localZ, Vector2i out) {
        float w = screen.size.x;
        float h = screen.size.y;
        BlockSide side = screen.side;

        // Compute (u, v) matching the per-face UV mapping in ScreenRenderer.renderScreen().
        // Each face has its own vertex→UV winding, so we must mirror that exactly here.
        double u, v;
        switch (side) {
            case SOUTH  -> { u = localX / w;         v = 1.0 - localY / h; }
            case NORTH  -> { u = 1.0 - localX / w;   v = 1.0 - localY / h; }
            case EAST   -> { u = 1.0 - localZ / w;   v = 1.0 - localY / h; }
            case WEST   -> { u = localZ / w;         v = 1.0 - localY / h; }
            case TOP    -> { u = localX / w;         v = 1.0 - localZ / h; }
            case BOTTOM -> { u = localX / w;         v = 1.0 - localZ / h; }
            default     -> { u = 0; v = 0; }
        }

        u = Math.max(0.0, Math.min(1.0, u));
        v = Math.max(0.0, Math.min(1.0, v));

        switch (screen.rotation) {
            case ROT_90  -> { double tu = u; u = v; v = 1.0 - tu; }
            case ROT_180 -> { u = 1.0 - u; v = 1.0 - v; }
            case ROT_270 -> { double tu = u; u = 1.0 - v; v = tu; }
        }
        out.x = Math.max(0, Math.min((int) (u * screen.resolution.x), screen.resolution.x - 1));
        out.y = Math.max(0, Math.min((int) (v * screen.resolution.y), screen.resolution.y - 1));
    }

    public void clickAt(Player player, BlockSide side, BlockHitResult hit) {
        ScreenData screen = getScreen(side);
        if (screen == null || screen.browser == null) return;

        Vec3 hitLoc = hit.getLocation();
        double localX = hitLoc.x - worldPosition.getX();
        double localY = hitLoc.y - worldPosition.getY();
        double localZ = hitLoc.z - worldPosition.getZ();

        Vector2i clickPos = new Vector2i();
        hitToScreenCoords(screen, localX, localY, localZ, clickPos);

        long now = System.currentTimeMillis();
        int clickCount = (now - screen.lastClickTime < 500) ? 2 : 1;
        screen.lastClickTime = now;

        MCEFHelper.sendMouseClick(screen.browser, clickPos.x, clickPos.y, 0, false, clickCount);
        MCEFHelper.sendMouseClick(screen.browser, clickPos.x, clickPos.y, 0, true, clickCount);
    }

    public boolean handleMouseEvent(BlockSide side, double hitX, double hitY, double hitZ) {
        ScreenData screen = getScreen(side);
        if (screen == null || screen.browser == null) return false;

        double localX = hitX - worldPosition.getX();
        double localY = hitY - worldPosition.getY();
        double localZ = hitZ - worldPosition.getZ();

        Vector2i mousePos = new Vector2i();
        hitToScreenCoords(screen, localX, localY, localZ, mousePos);

        MCEFHelper.sendMouseMove(screen.browser, mousePos.x, mousePos.y, false);
        return true;
    }

    // === Keyboard Input ===

    public void type(String text) {
        for (ScreenData screen : screens) {
            if (screen.browser != null) {
                for (char c : text.toCharArray()) MCEFHelper.sendKeyEvent(screen.browser, c);
            }
        }
    }

    // === URL Processing ===

    public static String url(String input) throws IOException {
        if (input.startsWith("mod://") || input.startsWith("webdisplays://")) return input;
        if (!input.startsWith("http://") && !input.startsWith("https://")) return "https://" + input;
        return input;
    }

    // === Lifecycle ===

    public boolean isLoaded() { return loaded; }

    public void load() {
        // NBT loading only; browser created lazily via activate() or renderer
        loaded = true;
    }

    public boolean needsBrowserRetry() {
        if (level == null || !level.isClientSide || !MCEFHelper.isMCEFAvailable()) return false;
        if (!MCEFHelper.isMCEFInitialized()) return true;
        for (ScreenData screen : screens) {
            if (screen.browser == null) return true;
        }
        return false;
    }

    public boolean retryCreateBrowsers() {
        if (level == null || !level.isClientSide || !MCEFHelper.isMCEFAvailable()) return false;
        if (!MCEFHelper.isMCEFInitialized()) return false;
        boolean created = false;
        for (ScreenData screen : screens) {
            if (screen.browser == null) {
                created |= createBrowserIfNeeded(screen);
            }
        }
        return created;
    }

    /** Create browser for one screen, respecting distance threshold. */
    private boolean createBrowserIfNeeded(ScreenData screen) {
        if (screen.browser != null) return false;
        WebDisplays wd = WebDisplays.getInstance();
        double maxDist = wd.loadDistance2 * 16.0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        double dist = distanceTo(mc.player.position());
        if (dist > maxDist) return false;
        return doCreateBrowser(screen);
    }

    private boolean doCreateBrowser(ScreenData screen) {
        String loadUrl = ScreenData.DEFAULT_URL;
        if (screen.url != null && !screen.url.isEmpty()) {
            try { loadUrl = url(screen.url); } catch (IOException e) { Log.warning("Invalid URL: {}", screen.url); }
        }
        screen.browser = MCEFHelper.createBrowser(loadUrl, false, screen.resolution.x, screen.resolution.y);
        if (screen.browser != null) {
            screen.appliedUrl = screen.url; // track URL the browser was created with
            Log.info("Created browser for screen at {} side {}", worldPosition, screen.side);
            injectScripts(screen.browser);
            doTurnOnAnim = true;
            turnOnStartTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /**
     * Kill the old browser process and create a new one with the current URL.
     * Called when URL changes to ensure the old webpage process is fully destroyed
     * (not just navigated). On server-side, browsers don't exist so this is a no-op.
     */
    public void reloadBrowser(BlockSide side) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        // Kill old browser process (3-step kill: mute JS → about:blank → browser.close)
        if (screen.browser != null) {
            Log.info("Reloading browser for screen at {} side {} — killing old process", worldPosition, side);
            screen.unload();
        }
        // Create new browser with updated URL (if on client and MCEF is ready)
        if (level != null && level.isClientSide && MCEFHelper.isMCEFAvailable() && MCEFHelper.isMCEFInitialized()) {
            createBrowserIfNeeded(screen);
        }
    }

    /** Activate: create browsers for all screens (called when player is in range). */
    public void activate() {
        if (level == null || !level.isClientSide || !MCEFHelper.isMCEFAvailable()) return;
        if (!MCEFHelper.isMCEFInitialized()) return;
        for (ScreenData screen : screens) {
            if (screen.browser == null) {
                doCreateBrowser(screen);
            }
        }
    }

    /** Deactivate: destroy all browsers (called when player is out of range). */
    public void deactivate() {
        int closed = 0;
        for (ScreenData screen : screens) {
            if (screen.browser != null) {
                screen.unload();
                closed++;
            }
        }
        if (closed > 0) {
            Log.info("Deactivated screen at {} - closed {} browser(s), player out of range", worldPosition, closed);
        }
        loaded = false;
    }

    /** Check distance from player and activate/deactivate accordingly. */
    public void checkDistanceLifecycle() {
        if (level == null || !level.isClientSide) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        WebDisplays wd = WebDisplays.getInstance();
        double dist = distanceTo(mc.player.position());
        double loadDist = wd.loadDistance2 * 16.0;
        double unloadDist = wd.unloadDistance2 * 16.0;
        if (!isLoaded()) {
            if (dist <= loadDist) activate();
        } else {
            if (dist > unloadDist) {
                deactivate();
            } else {
                // Use Chebyshev distance (cube) for audio range based on screen dimensions
                double dx = Math.abs(mc.player.getX() - worldPosition.getX());
                double dy = Math.abs(mc.player.getY() - worldPosition.getY());
                double dz = Math.abs(mc.player.getZ() - worldPosition.getZ());
                double chebyshevDist = Math.max(dx, Math.max(dy, dz));
                updateAudioVolume(chebyshevDist);
            }
        }
    }

    /**
     * Update browser audio volume based on Chebyshev distance from the player.
     * Audio range is proportional to each screen's width/height in blocks:
     *   range = max(width, height) * 2.0 blocks (Chebyshev radius).
     * Volume: 65% at center, linearly decays to 0% at the range edge.
     */
    public void updateAudioVolume(double chebyshevDist) {
        final float MAX_VOLUME = 0.65f; // 65% of maximum
        for (ScreenData screen : screens) {
            if (screen.browser == null) continue;
            // Audio range scales with screen size: max dimension * 2 blocks
            double audioRange = Math.max(screen.size.x, screen.size.y) * 2.0;
            float volume;
            if (chebyshevDist <= audioRange) {
                // Linear decay: 65% at 0 blocks → 0% at audioRange
                volume = MAX_VOLUME * (1.0f - (float) (chebyshevDist / audioRange));
            } else {
                volume = 0.0f;
            }
            MCEFHelper.injectJavascript(screen.browser, buildVolumeJS(volume));
        }
    }

    public void unload() { deactivate(); }

    /**
     * Disable a specific side's screen: close browser AND remove it from screens list.
     * Called by Forge's TurnOffControl.handleClient (equivalent: our DESTROY S2C receiver).
     * This is the ONLY reliable way to kill client browsers on sync/turnoff.
     */
    public void disableScreen(BlockSide side) {
        int idx = -1;
        for (int i = 0; i < screens.size(); i++) {
            if (screens.get(i).side == side) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        ScreenData remove = screens.get(idx);

        if (remove.browser != null) {
            remove.unload(); // MCEFHelper.closeBrowser() → 三步 kill 进程
        }
        screens.remove(idx);
    }

    /**
     * Server-side entry point for destruction (Forge parity: onDestroy(Player)).
     * - Idempotent via 'destroyed' flag (multiple sides can trigger for same origin)
     * - Broadcasts DESTROY S2C packet (null side = turnOff all sides)
     * - Also locally kills browsers (client-side BEs only; server-side browsers=null → no-op)
     */
    public void onDestroy(net.minecraft.world.entity.player.Player ply) {
        if (destroyed) return; // 幂等：防止多次触发同一 origin
        destroyed = true;

        if (!screens.isEmpty()) {
            Log.info("Destroying screen block at {} - closing {} browser(s)", worldPosition, screens.size());
        }

        // 服务端：广播 DESTROY 给所有追踪玩家（side=-1 表示关闭所有 sides）
        if (level != null && !level.isClientSide && !screens.isEmpty()) {
            ScreenData firstScreen = screens.get(0);
            net.montoyo.wd.network.ServerNetHandler.broadcastDestroy(level, worldPosition, firstScreen.side.id);
        }

        // 本地清理：对每个 screen 调用 disableScreen（closeBrowser + 从列表移除）
        // 注意：用快照遍历，因为 disableScreen 会修改列表
        java.util.List<ScreenData> snapshot = new java.util.ArrayList<>(screens);
        for (ScreenData screen : snapshot) {
            disableScreen(screen.side);
        }

        if (level != null && level.isClientSide) {
            synchronized (clientScreens) {
                clientScreens.remove(this);
                clientScreensDirty = true;
            }
            synchronized (trackedScreens) {
                trackedScreens.remove(this);
            }
        }
        if (!screens.isEmpty()) {
            Log.info("Screen block destroyed at {} - all browsers closed", worldPosition);
        }
        screens.clear();
        loaded = false;
    }

    // 向后兼容：无参 onDestroy() 调用有参版本
    public void onDestroy() {
        onDestroy(null);
    }

    public static final String WINDOW_OPEN_OVERRIDE_JS = "if(typeof window.__wdPatched==='undefined'){window.__wdPatched=true;window.open=function(u){if(u&&typeof u==='string'&&u.startsWith('http')){window.location.href=u;return window}return null};document.addEventListener('click',function(e){var a=e.target.closest('a');if(a&&a.href&&a.target==='_blank'){e.preventDefault();window.location.href=a.href}},true)}";

    public static final String MUTE_AUDIO_JS = "(function(){try{document.querySelectorAll('video,audio').forEach(function(el){el.pause();el.muted=true;el.src=''});if(window.__wdAudioMuted)return;window.__wdAudioMuted=true;var OrigAC=window.AudioContext||window.webkitAudioContext;if(OrigAC){window.AudioContext=function(){var ctx=new OrigAC();ctx.suspend();return ctx};window.webkitAudioContext=window.AudioContext;try{OrigAC.prototype.resume=function(){return Promise.resolve()}}catch(e){}}}catch(e){}})()";

    /**
     * Set `<video>/<audio>` volume to the given value (0.0–1.0).
     * Does NOT permanently mute — volume can be changed later.
     */
    public static final String SET_VOLUME_JS_TEMPLATE =
        "(function(){var v=%1.2f;document.querySelectorAll('video,audio').forEach(function(e){e.muted=false;e.volume=v});" +
        "try{if(window.__wdAudioCtx&&window.__wdAudioCtx.state==='suspended')window.__wdAudioCtx.resume()}catch(e){}})()";

    /** Inject volume JS with a specific float volume. */
    public static String buildVolumeJS(float volume) {
        return String.format(SET_VOLUME_JS_TEMPLATE, Math.max(0f, Math.min(1f, volume)));
    }

    private void injectScripts(Object browser) {
        if (browser == null) return;
        MCEFHelper.injectJavascript(browser, WINDOW_OPEN_OVERRIDE_JS);
        // Do NOT inject MUTE_AUDIO_JS on initial load — let the browser play audio.
        // Volume will be controlled dynamically via updateAudioVolume().
    }

    public static void ensureWindowOpenOverride(Object browser) {
        if (browser == null) return;
        MCEFHelper.injectJavascript(browser, WINDOW_OPEN_OVERRIDE_JS);
    }

    /** Public static version of injectScripts for use from the renderer. */
    public static void injectScriptsGlobal(Object browser) {
        if (browser == null) return;
        MCEFHelper.injectJavascript(browser, WINDOW_OPEN_OVERRIDE_JS);
        // No MUTE_AUDIO_JS — volume is distance-controlled
    }

    // === Sound ===

    public void playSound(float volume) {
        if (level != null) {
            level.playLocalSound(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                    worldPosition.getZ() + 0.5, WDRegistries.KEYBOARD_TYPE, SoundSource.BLOCKS,
                    volume, 1.0f, false);
        }
    }

    // === Render Bounding Box ===

    public AABB getRenderBoundingBox() {
        if (renderBB == null) updateAABB();
        return renderBB != null ? renderBB : new AABB(worldPosition);
    }

    private void updateAABB() {
        if (screens.isEmpty()) {
            renderBB = new AABB(worldPosition);
            return;
        }
        double minX = worldPosition.getX(), minY = worldPosition.getY(), minZ = worldPosition.getZ();
        double maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;
        for (ScreenData screen : screens) {
            double endX = worldPosition.getX() + (screen.side.right.x * screen.size.x) + (screen.side.up.x * screen.size.y);
            double endY = worldPosition.getY() + (screen.side.right.y * screen.size.x) + (screen.side.up.y * screen.size.y);
            double endZ = worldPosition.getZ() + (screen.side.right.z * screen.size.x) + (screen.side.up.z * screen.size.y);
            minX = Math.min(minX, endX); minY = Math.min(minY, endY); minZ = Math.min(minZ, endZ);
            maxX = Math.max(maxX, endX); maxY = Math.max(maxY, endY); maxZ = Math.max(maxZ, endZ);
        }
        renderBB = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    // === NBT Serialization ===

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        ListTag screenList = new ListTag();
        for (ScreenData screen : screens) {
            CompoundTag screenTag = new CompoundTag();
            screenTag.putInt("side", screen.side.id);
            screenTag.putInt("resX", screen.resolution.x);
            screenTag.putInt("resY", screen.resolution.y);
            screenTag.putInt("sizeX", screen.size.x);
            screenTag.putInt("sizeY", screen.size.y);
            screenTag.putInt("rotation", screen.rotation.id);
            screenTag.putBoolean("autoVolume", screen.autoVolume);
            if (screen.owner != null) screenTag.putString("owner", screen.owner);
            if (screen.url != null) screenTag.putString("url", screen.url);
            screenList.add(screenTag);
        }
        tag.put("screens", screenList);
        tag.putFloat("ytVolume", ytVolume);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        // Preserve existing browsers across BE updates: save the old screen list
        // so we can match by side and keep the browser.
        List<ScreenData> oldScreens = new ArrayList<>(screens);
        screens.clear();

        if (tag.contains("screens")) {
            ListTag screenList = tag.getList("screens", Tag.TAG_COMPOUND);
            for (int i = 0; i < screenList.size(); i++) {
                CompoundTag screenTag = screenList.getCompound(i);
                BlockSide side = BlockSide.fromInt(screenTag.getInt("side"));
                Vector2i res = new Vector2i(screenTag.getInt("resX"), screenTag.getInt("resY"));
                Vector2i size = new Vector2i(screenTag.getInt("sizeX"), screenTag.getInt("sizeY"));
                Rotation rot = Rotation.fromInt(screenTag.getInt("rotation"));
                boolean autoVol = screenTag.getBoolean("autoVolume");
                String owner = screenTag.contains("owner") ? screenTag.getString("owner") : null;
                String url = screenTag.contains("url") ? screenTag.getString("url") : null;
                String newUrl = (url != null && !url.isEmpty()) ? url : ScreenData.DEFAULT_URL;

                ScreenData screen = new ScreenData(side, res, size, owner);
                screen.rotation = rot;
                screen.autoVolume = autoVol;
                screen.url = newUrl;

                // Try to preserve the existing browser for this side.
                // Always preserve the browser regardless of URL change — URL sync
                // is handled exclusively by ACTION_SET_URL packets, not by load().
                ScreenData old = null;
                for (ScreenData sc : oldScreens) {
                    if (sc.side == side) { old = sc; break; }
                }
                if (old != null && old.browser != null) {
                    screen.browser = old.browser;
                    screen.appliedUrl = old.appliedUrl;
                    screen.lastUrl = old.lastUrl;
                    screen.lastClickTime = old.lastClickTime;
                }

                screens.add(screen);
            }
        } else {
            // No screens tag in NBT — this can happen in a race condition where
            // sendBlockUpdated() fires before the new screen data is serialized.
            // Preserve the old screens to avoid losing client-side state.
            // (E.g., client calls addScreen() → sendBlockUpdated() → load() fires
            //  but the NBT doesn't yet contain the newly added screen.)
            screens.addAll(oldScreens);
        }
        if (tag.contains("ytVolume")) {
            ytVolume = tag.getFloat("ytVolume");
        }
        updateAABB();
        if (level != null && level.isClientSide) {
            load();
        }
    }

    // === Distance Calculation ===

    public double distanceTo(Vec3 position) {
        double dist = Double.POSITIVE_INFINITY;
        for (ScreenData scrn : screens) {
            Vector3d p = new Vector3d(
                (scrn.side.right.x * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.x) / 2.0,
                (scrn.side.right.y * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.y) / 2.0,
                (scrn.side.right.z * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.z) / 2.0
            ).add(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
            dist = Math.min(dist, position.distanceTo(new Vec3(p.x, p.y, p.z)));
        }
        return dist;
    }

    // === Getters ===

    public List<ScreenData> getScreens() { return screens; }
    public float getYtVolume() { return ytVolume; }
    public void setYtVolume(float vol) { this.ytVolume = vol; setChanged(); }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
