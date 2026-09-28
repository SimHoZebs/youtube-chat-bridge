package com.ytchat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Client-only bridge: YouTube live chat -&gt; Minecraft client chat.
 * All polling runs on a background thread; chat output is posted on the client thread.
 */
@Mod(YtChatMod.MODID)
public class YtChatMod {
    public static final String MODID = "ytchat";
    public static final Logger LOGGER = LogUtils.getLogger();

    public YtChatMod(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, YtChatConfig.SPEC);
    }

    /** True when running on the physical client with a player present. */
    public static boolean isClientReady() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.player != null;
    }
}
