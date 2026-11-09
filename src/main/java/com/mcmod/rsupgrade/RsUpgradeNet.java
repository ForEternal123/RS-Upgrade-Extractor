package com.mcmod.rsupgrade;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 客户端 → 服务端：请求执行一次"快捷提取"。
 *
 * 只有客户端按快捷键时才会发这个包；服务端收到后：
 *   1. 检查是不是玩家本人
 *   2. 做一次节流（1 秒内只处理一次，防止连点）
 *   3. 执行 背包 + RS 的一次提取
 */
public final class RsUpgradeNet {

    private static final String PROTOCOL = "1";
    private static int nextId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(RsUpgradeMod.MODID, "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    private RsUpgradeNet() {
    }

    public static void init() {
        CHANNEL.registerMessage(
            nextId++,
            QuickExtract.class,
            (msg, buf) -> {
            },
            buf -> new QuickExtract(),
            (msg, ctx) -> {
                ctx.get().enqueueWork(() -> {
                    ServerPlayer player = ctx.get().getSender();
                    if (player == null) return;
                    RsUpgradeMod.handleQuickExtract(player);
                });
                ctx.get().setPacketHandled(true);
            },
            Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
    }

    /** 空包：不需要携带参数 */
    public static class QuickExtract {
        public QuickExtract() {
        }
    }
}
