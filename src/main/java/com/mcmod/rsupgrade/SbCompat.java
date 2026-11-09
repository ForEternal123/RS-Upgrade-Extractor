package com.mcmod.rsupgrade;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 精妙背包兼容层。
 *
 * 为什么需要它：
 *   精妙背包的「背包内容」和「升级槽」不一定存在物品 NBT 里——
 *   3.x 版本会按 UUID 把内容存到世界存档，物品 NBT 里只有一个 UUID。
 *   所以纯 NBT 扫描看不到升级和背包库存，必须走它的 capability。
 *
 * 单独的类：没装精妙背包时不会加载，避免 NoClassDefFoundError。
 */
final class SbCompat {

    private SbCompat() {
    }

    static boolean available() {
        return ModList.get().isLoaded("sophisticatedbackpacks");
    }

    /** 这个物品是不是精妙背包 */
    static boolean isBackpack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            return stack.getCapability(
                net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                    .getCapabilityInstance()).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object wrapperOf(ItemStack stack) {
        try {
            var opt = stack.getCapability(
                net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                    .getCapabilityInstance());
            if (opt.isPresent()) {
                return opt.resolve().orElse(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 背包自己的库存（放普通物品的那块） */
    static IItemHandler storageOf(ItemStack stack) {
        Object wrapper = wrapperOf(stack);
        if (wrapper == null) return null;
        try {
            return ((net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper) wrapper).getInventoryHandler();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 背包升级槽里，所有"自带物品栏"的升级的 handler。
     * 比如合成升级的 craftingInventory、唱片机升级的 discInventory。
     */
    static List<IItemHandler> upgradeInventoriesOf(ItemStack stack) {
        List<IItemHandler> out = new ArrayList<>();
        Object wrapper = wrapperOf(stack);
        if (wrapper == null) return out;

        try {
            var storage = (net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper) wrapper;
            var uh = storage.getUpgradeHandler();

            // 触发一次刷新，确保 wrapper 已经建立
            try {
                uh.refreshWrappersThatImplementAndTypeWrappers();
            } catch (Throwable ignored) {
            }

            for (var uw : uh.getSlotWrappers().values()) {
                if (uw == null) continue;
                try {
                    var m = uw.getClass().getMethod("getInventory");
                    Object r = m.invoke(uw);
                    if (r instanceof IItemHandler handler) {
                        out.add(handler);
                    }
                } catch (NoSuchMethodException ignored) {
                    // 这类升级没有物品栏
                } catch (Throwable t) {
                    RsUpgradeMod.LOGGER.debug("[rsupgrade] 读升级栏失败: {}", t.toString());
                }
            }
        } catch (Throwable t) {
            RsUpgradeMod.LOGGER.debug("[rsupgrade] 读背包升级槽失败: {}", t.toString());
        }
        return out;
    }

    /** 背包升级槽里有哪些升级（用于调试输出） */
    static List<String> upgradeIds(ItemStack stack) {
        List<String> out = new ArrayList<>();
        Object wrapper = wrapperOf(stack);
        if (wrapper == null) return out;
        try {
            var storage = (net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper) wrapper;
            var uh = storage.getUpgradeHandler();
            try {
                uh.refreshWrappersThatImplementAndTypeWrappers();
            } catch (Throwable ignored) {
            }
            for (var uw : uh.getSlotWrappers().values()) {
                if (uw == null) continue;
                try {
                    var st = uw.getUpgradeStack();
                    out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(st.getItem()).toString());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
