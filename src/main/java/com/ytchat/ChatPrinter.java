package com.ytchat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Formats and posts all mod output to the client's chat HUD.
 * Must be safe to call from any thread — everything hops to the client thread.
 */
public final class ChatPrinter {
    private ChatPrinter() {}

    private static String prefix() {
        String p = YtChatConfig.PREFIX.get();
        return p == null || p.isBlank() ? "[YT]" : p.trim();
    }

    public static void info(String msg) {
        post(Component.literal(prefix() + " ").withStyle(s -> s.withColor(0x55FFFF))
                .append(Component.literal(msg).withStyle(s -> s.withColor(0xFFFFFF))));
    }

    public static void success(String msg) {
        post(Component.literal(prefix() + " ").withStyle(s -> s.withColor(0x55FFFF))
                .append(Component.literal(msg).withStyle(s -> s.withColor(0x55FF55))));
    }

    public static void warn(String msg) {
        post(Component.literal(prefix() + " ").withStyle(s -> s.withColor(0x55FFFF))
                .append(Component.literal(msg).withStyle(s -> s.withColor(0xFFAA00))));
    }

    public static void error(String msg) {
        post(Component.literal(prefix() + " ").withStyle(s -> s.withColor(0x55FFFF))
                .append(Component.literal(msg).withStyle(s -> s.withColor(0xFF5555))));
    }

    /** Prints one YouTube chat message. */
    public static void chat(InnerTubeClient.ChatMessage m) {
        int nameColor = 0xFFDD88;
        String tag = "";
        if (m.owner()) {
            nameColor = 0xFFAA00;
            tag = " [OWNER]";
        } else if (m.moderator()) {
            nameColor = 0x5555FF;
            tag = " [MOD]";
        } else if (m.sponsor()) {
            nameColor = 0x55FF55;
        }
        final int color = nameColor;
        MutableComponent line = Component.literal(prefix() + " <").withStyle(s -> s.withColor(0x55FFFF))
                .append(Component.literal(m.author()).withStyle(s -> s.withColor(color)))
                .append(Component.literal(">" + tag + " ").withStyle(s -> s.withColor(0x55FFFF)))
                .append(Component.literal(m.text()).withStyle(s -> s.withColor(m.superChat() ? 0xFFFF55 : 0xFFFFFF)));
        if (m.superChat()) {
            line = Component.literal("[$] ").withStyle(s -> s.withColor(0xFFFF55)).append(line);
        }
        post(line);
    }

    public static void membership(InnerTubeClient.ChatMessage m) {
        post(Component.literal(prefix() + " ").withStyle(s -> s.withColor(0x55FFFF))
                .append(Component.literal("★ " + m.author() + ": " + m.text())
                        .withStyle(s -> s.withColor(0x55FF55))));
    }

    private static void post(Component c) {
        runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(c);
            }
        });
    }

    private static void runOnClient(Runnable r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.isSameThread()) {
            r.run();
        } else {
            mc.execute(r);
        }
    }
}
