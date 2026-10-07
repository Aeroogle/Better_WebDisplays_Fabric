package net.montoyo.wd.client;

import net.montoyo.wd.utilities.Log;

/**
 * Handles webdisplays:// custom URL scheme.
 * This is a stub - full implementation requires MCEF integration.
 */
public class WDScheme {
    private final String url;

    public WDScheme(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    /**
     * Process a webdisplays:// URL and return content
     */
    public static byte[] handleRequest(String url) {
        if (url.startsWith("webdisplays://")) {
            String path = url.substring("webdisplays://".length());
            Log.info("WDScheme request: {}", path);
            // TODO: Implement miniserv file handling
            return null;
        }
        return null;
    }
}