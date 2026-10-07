package net.montoyo.wd.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.montoyo.wd.utilities.Log;

/**
 * /screentp &lt;player&gt; — 允许任意玩家（无需 OP）传送到指定玩家身边。
 * 支持跨维度传送。
 */
public class ScreenTpCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("screentp")
                .requires(source -> source.hasPermission(0)) // 无需 OP 权限
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() ->
                            Component.literal("\u00a7e\u7528\u6cd5: /screentp <\u73a9\u5bb6\u540d>"), false);
                    return 1;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String targetName = StringArgumentType.getString(ctx, "player");
                            return teleportToPlayer(ctx.getSource(), targetName);
                        }))
        );
    }

    private static int teleportToPlayer(CommandSourceStack source, String targetName) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("\u00a7c\u670d\u52a1\u5668\u672a\u5c31\u7eea"));
            return 0;
        }

        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            source.sendFailure(Component.literal("\u00a7c\u627e\u4e0d\u5230\u73a9\u5bb6: " + targetName));
            return 0;
        }

        if (target.getUUID().equals(player.getUUID())) {
            source.sendFailure(Component.literal("\u00a7c\u4e0d\u80fd\u4f20\u9001\u5230\u81ea\u5df1"));
            return 0;
        }

        ServerLevel targetLevel = target.serverLevel();
        double tx = target.getX();
        double ty = target.getY();
        double tz = target.getZ();
        float tyaw = target.getYRot();
        float tpitch = target.getXRot();

        if (!targetLevel.dimension().equals(player.level().dimension())) {
            // 跨维度传送
            player.teleportTo(targetLevel, tx, ty, tz, tyaw, tpitch);
        } else {
            // 同维度传送
            player.teleportTo(tx, ty, tz);
        }

        ServerPlayer finalTarget = target;
        source.sendSuccess(() ->
                Component.literal("\u00a7a\u5df2\u4f20\u9001\u5230 " + finalTarget.getName().getString()), true);
        Log.info("Player {} teleported to {}", player.getName().getString(), targetName);
        return 1;
    }
}
