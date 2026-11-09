package com.mcmod.rsupgrade;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端：快捷键。
 *
 * 默认 K 键，可以在「选项 → 控制 → 按键绑定」里改。
 * 按下时给服务端发一个包，服务端执行一次「背包 + RS」提取。
 */
@Mod.EventBusSubscriber(modid = RsUpgradeMod.MODID, value = Dist.CLIENT)
public final class ClientKeybind {

    private static KeyMapping extractKey;

    private ClientKeybind() {
    }

    @Mod.EventBusSubscriber(modid = RsUpgradeMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registrar {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            extractKey = new KeyMapping(
                "key.rsupgrade.extract",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "key.categories.rsupgrade"
            );
            event.register(extractKey);
        }
    }

    @Mod.EventBusSubscriber(modid = RsUpgradeMod.MODID, value = Dist.CLIENT)
    public static final class TickHandler {
        private static long lastSent = 0L;

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (extractKey == null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;

            while (extractKey.consumeClick()) {
                long now = System.currentTimeMillis();
                if (now - lastSent < 500L) continue;   // 防连点
                lastSent = now;

                if (mc.getConnection() == null) continue;
                var conn = mc.getConnection().getConnection();
                if (conn == null || !RsUpgradeNet.CHANNEL.isRemotePresent(conn)) {
                    mc.player.displayClientMessage(
                        Component.literal("§c[rsupgrade] 服务端没有装这个 mod"), true);
                    continue;
                }
                RsUpgradeNet.CHANNEL.sendToServer(new RsUpgradeNet.QuickExtract());
                mc.player.displayClientMessage(
                    Component.literal("§7[rsupgrade] 提取中…"), true);
            }
        }
    }
}
