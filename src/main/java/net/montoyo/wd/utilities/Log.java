package net.montoyo.wd.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Log {
    private static final Logger LOGGER = LoggerFactory.getLogger("WebDisplays");

    public static void info(String msg, Object... args) {
        LOGGER.info(msg, args);
    }

    public static void warning(String msg, Object... args) {
        LOGGER.warn(msg, args);
    }

    public static void error(String msg, Object... args) {
        LOGGER.error(msg, args);
    }

    public static void warningEx(String msg, Throwable t, Object... args) {
        LOGGER.warn(msg, args);
    }

    public static void debug(String msg, Object... args) {
        LOGGER.debug(msg, args);
    }
}