package com.ytchat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client commands. Registered on the FORGE bus (RegisterClientCommandsEvent fires there).
 * No accounts anywhere: the only setup is {@code /ytchat set channel @Handle} once.
 * <ul>
 *   <li>/ytchat start — auto-detect the configured channel's live broadcast</li>
 *   <li>/ytchat start &lt;videoId|URL|@handle&gt; — watch something specific</li>
 *   <li>/ytchat stop / status</li>
 *   <li>/ytchat set channel &lt;value&gt;</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = YtChatMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class YtChatCommands {
    private YtChatCommands() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ytchat")
                .then(Commands.literal("start")
                        .executes(ctx -> start(ctx, ""))
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("stop").executes(YtChatCommands::stop))
                .then(Commands.literal("status").executes(YtChatCommands::status))
                .then(Commands.literal("set")
                        .then(Commands.literal("channel")
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> setChannel(ctx,
                                                StringArgumentType.getString(ctx, "value"))))))
                .executes(YtChatCommands::status));
    }

    /** Stop polling when leaving a world/server so no background work leaks across sessions. */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ChatPoller.instance().stop();
    }

    /**
     * Greet on every login while no channel is configured, so the setup step
     * can't be missed. Stops nagging the moment a channel is set.
     */
    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!YtChatConfig.hasChannel()) {
            ChatPrinter.warn("Welcome to YouTube Chat Bridge! Set a channel to watch:");
            ChatPrinter.info("Run /ytchat set channel @YourHandle  (then /ytchat start when live)");
        }
    }

    private static int start(CommandContext<CommandSourceStack> ctx, String target) {
        ChatPoller.instance().start(target == null ? "" : target);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        boolean was = ChatPoller.instance().isRunning();
        ChatPoller.instance().stop();
        ctx.getSource().sendSuccess(
                () -> Component.literal(was ? "[YT] Stopped watching chat." : "[YT] Not running."), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        String channel = YtChatConfig.hasChannel() ? YtChatConfig.CHANNEL.get() : "NOT SET (/ytchat set channel @Handle)";
        ctx.getSource().sendSuccess(
                () -> Component.literal("[YT] Channel: " + channel
                        + " — " + ChatPoller.instance().statusLine() + "."), false);
        return 1;
    }

    private static int setChannel(CommandContext<CommandSourceStack> ctx, String value) {
        value = value.trim();
        if (value.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[YT] Channel must not be empty."));
            return 0;
        }
        try {
            YtChatConfig.CHANNEL.set(value);
            YtChatConfig.SPEC.save();
            ChatPrinter.success("Channel saved. Run /ytchat start whenever you're live.");
            ctx.getSource().sendSuccess(() -> Component.literal("[YT] Channel saved."), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[YT] Could not save config: " + e.getMessage()));
            return 0;
        }
    }
}
