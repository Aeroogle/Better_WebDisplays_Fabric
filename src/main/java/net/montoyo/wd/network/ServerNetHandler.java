package net.montoyo.wd.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class ServerNetHandler {

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ScreenActionPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
            ScreenActionPayload payload = ScreenActionPayload.decode(buf);
            Level level = player.level();
            if (!level.isLoaded(payload.pos)) return;

            server.execute(() -> {
                BlockEntity be = level.getBlockEntity(payload.pos);
                if (!(be instanceof ScreenBlockEntity screen)) return;

                BlockSide side = BlockSide.fromInt(payload.sideOrdinal);
                String owner = player.getName().getString();

                switch (payload.action) {
                    case ScreenActionPayload.ACTION_ADD_SCREEN -> {
                        // Single screen constraint: reject if any screen already exists
                        if (screen.screenCount() > 0) {
                            Log.warning("Player {} tried to add screen but one already exists at {}",
                                    owner, payload.pos);
                            return;
                        }
                        if (!screen.hasScreen(side)) {
                            int bw = 2, bh = 2;
                            String[] parts = payload.extraData.split(",");
                            if (parts.length == 2) {
                                try {
                                    bw = Math.max(1, Math.min(16, Integer.parseInt(parts[0])));
                                    bh = Math.max(1, Math.min(16, Integer.parseInt(parts[1])));
                                } catch (NumberFormatException e) {}
                            }
                            Vector2i size = new Vector2i(bw, bh);
                            Vector2i res = new Vector2i(bw * 160, bh * 160);
                            screen.addScreen(side, res, size, owner);
                            screen.setChanged();
                            level.sendBlockUpdated(payload.pos, level.getBlockState(payload.pos),
                                    level.getBlockState(payload.pos), 3);
                        }
                    }
                    case ScreenActionPayload.ACTION_REMOVE_SCREEN -> {
                        screen.removeScreen(side);
                    }
                    case ScreenActionPayload.ACTION_SET_URL -> {
                        // Server-side authoritative URL update + persist via setChanged().
                        // Then broadcast to OTHER tracking players so they reloadBrowser() locally.
                        // (Sender already applied the URL locally in GuiSetURL/GuiScreenConfig,
                        //  matching Forge's PacketDistributor.NEAR excluding sender behavior is
                        //  achieved by includeSender=false here.)
                        screen.setScreenURL(side, payload.extraData);
                        broadcastToTracking(level, payload, player, false);
                    }
                    case ScreenActionPayload.ACTION_SET_RESOLUTION -> {
                        String[] parts = payload.extraData.split(",");
                        if (parts.length == 2) {
                            try {
                                int w = Integer.parseInt(parts[0]);
                                int h = Integer.parseInt(parts[1]);
                                screen.setResolution(side, new Vector2i(w, h));
                                // Broadcast to OTHER tracking players so they resize their browser locally.
                                // (Sender already applied the resolution locally in GuiScreenConfig.)
                                broadcastToTracking(level, payload, player, false);
                            } catch (NumberFormatException e) {
                                Log.warning("Invalid resolution data: {}", payload.extraData);
                            }
                        }
                    }
                    case ScreenActionPayload.ACTION_SET_ROTATION -> {
                        try {
                            int rot = Integer.parseInt(payload.extraData);
                            screen.setRotation(side, Rotation.fromInt(rot));
                            // Broadcast to OTHER tracking players so they apply rotation locally.
                            broadcastToTracking(level, payload, player, false);
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid rotation data: {}", payload.extraData);
                        }
                    }
                    // === Multiplayer sync: broadcast to all tracking players (except sender) ===
                    case ScreenActionPayload.ACTION_CLICK,
                         ScreenActionPayload.ACTION_KEY_PRESS,
                         ScreenActionPayload.ACTION_KEY_RELEASE,
                         ScreenActionPayload.ACTION_KEY_TYPE,
                         ScreenActionPayload.ACTION_MOUSE_DOWN,
                         ScreenActionPayload.ACTION_MOUSE_UP,
                         ScreenActionPayload.ACTION_MOUSE_MOVE,
                         ScreenActionPayload.ACTION_MOUSE_WHEEL -> {
                        broadcastToTracking(level, payload, player, false);
                    }
                    case ScreenActionPayload.ACTION_DESTROY -> {
                        // DESTROY is broadcast to ALL players including sender
                        broadcastToTracking(level, payload, player, true);
                    }
                }
            });
        });
    }

    /**
     * Broadcast a payload to all players tracking the chunk at the given position.
     * NOTE: A fresh FriendlyByteBuf must be created for EACH player — reusing a
     * single buffer causes the reader index to advance after the first send,
     * so all subsequent players receive empty/corrupted data.
     * @param level The server level
     * @param payload The payload to broadcast
     * @param sender The player who sent the original packet (excluded if includeSender=false)
     * @param includeSender If true, sender also receives the broadcast
     */
    private static void broadcastToTracking(Level level, ScreenActionPayload payload,
                                            ServerPlayer sender, boolean includeSender) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        Iterable<ServerPlayer> trackingPlayers = PlayerLookup.tracking(serverLevel, payload.pos);
        for (ServerPlayer player : trackingPlayers) {
            if (!includeSender && player == sender) continue;
            // encode() creates a new buffer each call — safe for multi-player broadcast
            ServerPlayNetworking.send(player, ScreenActionPayload.CHANNEL,
                    ScreenActionPayload.encode(payload));
        }
        Log.debug("Broadcast {} to tracking players at {}", payload.action, payload.pos);
    }

    /**
     * Broadcast DESTROY to all tracking players (called from ScreenBlockEntity.onDestroy).
     * sideOrdinal=-1 (Forge TurnOffControl parity: side=null) means close ALL sides on the target BE.
     */
    public static void broadcastDestroy(Level level, BlockPos pos, int sideOrdinal) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Use -1 to signal "all sides" — matches Forge's turnOff(side=null) semantics
        ScreenActionPayload payload = ScreenActionPayload.destroy(pos, -1);
        Iterable<ServerPlayer> trackingPlayers = PlayerLookup.tracking(serverLevel, pos);
        for (ServerPlayer player : trackingPlayers) {
            ServerPlayNetworking.send(player, ScreenActionPayload.CHANNEL,
                    ScreenActionPayload.encode(payload));
        }
        Log.info("Broadcast DESTROY (all sides) for screen at {} to all tracking players", pos);
    }
}
