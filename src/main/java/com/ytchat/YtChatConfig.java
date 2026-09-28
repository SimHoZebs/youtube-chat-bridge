package com.ytchat;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config (config/ytchat-client.toml).
 * Zero-setup: the only thing to set is your channel ({@code @handle} etc.).
 * No Google Cloud project, no API key, no login — for anyone using the mod.
 */
public class YtChatConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> CHANNEL;
    public static final ForgeConfigSpec.BooleanValue SHOW_SUPERCHAT;
    public static final ForgeConfigSpec.BooleanValue SHOW_MEMBERSHIPS;
    public static final ForgeConfigSpec.ConfigValue<String> PREFIX;
    public static final ForgeConfigSpec.IntValue MIN_POLL_SECONDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("YouTube Chat Bridge - client-side config. No accounts needed anywhere.");
        CHANNEL = b.comment("Your channel for auto-detect: @handle, channel/UC..., c/..., user/...",
                "Used by /ytchat start with no arguments. Viewers can set any channel they want to watch.")
                .define("channel", "");
        SHOW_SUPERCHAT = b.comment("Show Super Chats / Super Stickers with highlight.").define("showSuperChat", true);
        SHOW_MEMBERSHIPS = b.comment("Show new-member / milestone / gift messages.").define("showMemberships", true);
        PREFIX = b.comment("Chat line prefix for YouTube messages.").define("prefix", "[YT]");
        MIN_POLL_SECONDS = b.comment("Lower bound between chat polls; YouTube also dictates an interval per response.")
                .defineInRange("minPollSeconds", 5, 2, 60);
        SPEC = b.build();
    }

    public static boolean hasChannel() {
        return !CHANNEL.get().isBlank();
    }
}
