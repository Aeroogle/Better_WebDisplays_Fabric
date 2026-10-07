package net.montoyo.wd.entity;

import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class ScreenData {
    public BlockSide side;
    public Vector2i resolution;
    public Vector2i size;
    public Object browser; // MCEFBrowser at runtime via reflection
    public int mouseType;
    public Rotation rotation;
    public boolean autoVolume;
    public String owner;
    public String url;
    public String appliedUrl = null; // URL the current browser was created with (null if no browser); NOT serialized
    public long lastClickTime;
    public String lastUrl = ""; // for detecting page navigation
    public double zoomLevel = 1.0; // browser page zoom (1.0 = 100%)

    public static final String DEFAULT_URL = "";

    public ScreenData(BlockSide side, Vector2i resolution, Vector2i size, String owner) {
        this.side = side;
        this.resolution = resolution;
        this.size = size;
        this.owner = owner;
        this.url = DEFAULT_URL;
        this.rotation = Rotation.ROT_0;
        this.autoVolume = false;
        this.mouseType = 0;
        this.browser = null;
        this.lastClickTime = 0;
    }

    public boolean isLoaded() {
        return browser != null;
    }

    public void unload() {
        if (browser != null) {
            Log.info("Unloading browser for screen side {}", side);
            MCEFHelper.closeBrowser(browser);
            browser = null;
            Log.info("Browser destroyed for screen side {}", side);
        }
    }
}