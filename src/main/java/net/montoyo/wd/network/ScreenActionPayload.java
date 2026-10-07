package net.montoyo.wd.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1 version: Packet utility class (no CustomPacketPayload, no StreamCodec)
 */
public class ScreenActionPayload {

    public static final ResourceLocation CHANNEL = new ResourceLocation("webdisplays", "screen_action");

    // Action constants - screen management
    public static final String ACTION_ADD_SCREEN = "add_screen";
    public static final String ACTION_REMOVE_SCREEN = "remove_screen";
    public static final String ACTION_SET_URL = "set_url";
    public static final String ACTION_SET_RESOLUTION = "set_resolution";
    public static final String ACTION_SET_ROTATION = "set_rotation";

    // Action constants - multiplayer sync (broadcast)
    public static final String ACTION_CLICK = "click";           // extraData: "x,y,button,clickCount"
    public static final String ACTION_KEY_PRESS = "key_press";   // extraData: "keyCode,scanCode,modifiers"
    public static final String ACTION_KEY_RELEASE = "key_release"; // extraData: "keyCode,scanCode,modifiers"
    public static final String ACTION_KEY_TYPE = "key_type";     // extraData: "charCode"
    public static final String ACTION_DESTROY = "destroy";       // extraData: "" (broadcast to all)

    // Mouse event actions (Forge ClickControl.ControlType parity: DOWN/UP/MOVE)
    public static final String ACTION_MOUSE_DOWN = "mouse_down";  // extraData: "x,y,button,clickCount"
    public static final String ACTION_MOUSE_UP = "mouse_up";      // extraData: "x,y,button"
    public static final String ACTION_MOUSE_MOVE = "mouse_move";  // extraData: "x,y"
    public static final String ACTION_MOUSE_WHEEL = "mouse_wheel"; // extraData: "x,y,delta,modifiers"

    public final BlockPos pos;
    public final int sideOrdinal;
    public final String action;
    public final String extraData;

    public ScreenActionPayload(BlockPos pos, int sideOrdinal, String action, String extraData) {
        this.pos = pos;
        this.sideOrdinal = sideOrdinal;
        this.action = action;
        this.extraData = extraData;
    }

    // === Serialization ===

    public static FriendlyByteBuf encode(ScreenActionPayload pkt) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pkt.pos);
        buf.writeInt(pkt.sideOrdinal);
        buf.writeUtf(pkt.action);
        buf.writeUtf(pkt.extraData);
        return buf;
    }

    public static ScreenActionPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int sideOrdinal = buf.readInt();
        String action = buf.readUtf();
        String extraData = buf.readUtf();
        return new ScreenActionPayload(pos, sideOrdinal, action, extraData);
    }

    // === Static Factory Methods - Screen Management ===

    public static ScreenActionPayload addScreen(BlockPos pos, int sideOrdinal, String owner) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_ADD_SCREEN, owner);
    }

    public static ScreenActionPayload removeScreen(BlockPos pos, int sideOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_REMOVE_SCREEN, "");
    }

    public static ScreenActionPayload setUrl(BlockPos pos, int sideOrdinal, String url) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_URL, url);
    }

    public static ScreenActionPayload setResolution(BlockPos pos, int sideOrdinal, int width, int height) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_RESOLUTION, width + "," + height);
    }

    public static ScreenActionPayload setRotation(BlockPos pos, int sideOrdinal, int rotationOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_ROTATION, String.valueOf(rotationOrdinal));
    }

    // === Static Factory Methods - Multiplayer Sync ===

    public static ScreenActionPayload click(BlockPos pos, int sideOrdinal, int x, int y, int button, int clickCount) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_CLICK, x + "," + y + "," + button + "," + clickCount);
    }

    public static ScreenActionPayload keyPress(BlockPos pos, int sideOrdinal, int keyCode, int scanCode, int modifiers) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_KEY_PRESS, keyCode + "," + scanCode + "," + modifiers);
    }

    public static ScreenActionPayload keyRelease(BlockPos pos, int sideOrdinal, int keyCode, int scanCode, int modifiers) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_KEY_RELEASE, keyCode + "," + scanCode + "," + modifiers);
    }

    public static ScreenActionPayload keyType(BlockPos pos, int sideOrdinal, char codePoint) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_KEY_TYPE, String.valueOf((int) codePoint));
    }

    public static ScreenActionPayload destroy(BlockPos pos, int sideOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_DESTROY, "");
    }

    // === Static Factory Methods - Mouse Event Sync (Forge ClickControl parity) ===

    public static ScreenActionPayload mouseDown(BlockPos pos, int sideOrdinal, int x, int y, int button, int clickCount) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_MOUSE_DOWN, x + "," + y + "," + button + "," + clickCount);
    }

    public static ScreenActionPayload mouseUp(BlockPos pos, int sideOrdinal, int x, int y, int button) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_MOUSE_UP, x + "," + y + "," + button);
    }

    public static ScreenActionPayload mouseMove(BlockPos pos, int sideOrdinal, int x, int y) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_MOUSE_MOVE, x + "," + y);
    }

    public static ScreenActionPayload mouseWheel(BlockPos pos, int sideOrdinal, int x, int y, double delta, int modifiers) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_MOUSE_WHEEL, x + "," + y + "," + delta + "," + modifiers);
    }
}
