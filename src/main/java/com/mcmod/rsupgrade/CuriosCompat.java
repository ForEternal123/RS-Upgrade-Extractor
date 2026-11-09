package com.mcmod.rsupgrade;

import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Curios 背饰槽兼容层。
 *
 * 单独放一个类：没装 Curios 的时候，这个类根本不会被加载，
 * 也就不会触发 NoClassDefFoundError。
 */
final class CuriosCompat {

    private CuriosCompat() {
    }

    /** Curios 是否已安装（只看 mod 列表，不触发类加载） */
    static boolean available() {
        return ModList.get().isLoaded("curios");
    }

    /** 一个槽位引用：能读到 stack，也能把它写回去 */
    record Slot(IItemHandlerModifiable handler, int index) {
        ItemStack stack() {
            return handler.getStackInSlot(index);
        }

        void commit(ItemStack stack) {
            handler.setStackInSlot(index, stack);
        }
    }

    /** 收集玩家身上所有 Curios 槽（背饰、戒指、护符……） */
    static List<Slot> slots(ServerPlayer player) {
        List<Slot> out = new ArrayList<>();
        try {
            var opt = CuriosApi.getCuriosInventory(player);
            if (opt.isPresent()) {
                IItemHandler base = opt.resolve().get().getEquippedCurios();
                if (base instanceof IItemHandlerModifiable handler) {
                    for (int i = 0; i < handler.getSlots(); i++) {
                        out.add(new Slot(handler, i));
                    }
                }
            }
        } catch (Throwable t) {
            // Curios 版本差异或初始化未完成，忽略即可
            RsUpgradeMod.LOGGER.debug("[rsupgrade] 读取 Curios 槽失败: {}", t.toString());
        }
        return out;
    }
}
