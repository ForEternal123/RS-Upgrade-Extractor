package com.mcmod.rsupgrade;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RS Upgrade Extractor
 *
 * 把「合成升级」这类物品内部夹带的物品取出来，放进 RS 网络。
 * 支持两个来源：
 *   1) 存在 RS 网络里的升级物品       → /rsupgrade rs
 *   2) 玩家背包里携带的升级物品       → /rsupgrade bag
 *
 * 只认 NBT 里带 {Items:[...]} 结构的子标签（精妙背包的 craftingInventory 就是这个格式），
 * 不依赖精妙背包的 Java API，所以对其它同类物品也通用。
 */
@Mod(RsUpgradeMod.MODID)
public class RsUpgradeMod {

    public static final String MODID = "rsupgrade";
    public static final Logger LOGGER = LoggerFactory.getLogger("RSUPGRADE");

    /** 单次命令最多处理多少个物品，防止一条指令卡死服务器 */
    private static final int MAX_ITEMS_PER_COMMAND = 4096;

    public RsUpgradeMod() {
        MinecraftForge.EVENT_BUS.register(RsUpgradeMod.class);
        RsUpgradeNet.init();
        LOGGER.info("[rsupgrade] 已加载");
    }

    // =========================================================
    //  指令
    // =========================================================
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("rsupgrade")

                // /rsupgrade  —— 帮助 / 状态
                .executes(ctx -> help(ctx.getSource()))

                // /rsupgrade rs [关键字] [once|deep]
                .then(Commands.literal("rs")
                    .executes(ctx -> fromRs(ctx.getSource(), "", false))
                    .then(Commands.argument("keyword", StringArgumentType.greedyString())
                        .executes(ctx -> parseRsArgs(ctx.getSource(),
                            StringArgumentType.getString(ctx, "keyword")))))

                // /rsupgrade bag [关键字] [once|deep]
                .then(Commands.literal("bag")
                    .executes(ctx -> fromBag(ctx.getSource(), "", false))
                    .then(Commands.argument("keyword", StringArgumentType.greedyString())
                        .executes(ctx -> parseBagArgs(ctx.getSource(),
                            StringArgumentType.getString(ctx, "keyword")))))

                // /rsupgrade list <rs|bag> [关键字]
                .then(Commands.literal("list")
                    .then(Commands.literal("rs")
                        .executes(ctx -> listRs(ctx.getSource(), ""))
                        .then(Commands.argument("keyword", StringArgumentType.greedyString())
                            .executes(ctx -> listRs(ctx.getSource(),
                                StringArgumentType.getString(ctx, "keyword")))))
                    .then(Commands.literal("bag")
                        .executes(ctx -> listBag(ctx.getSource(), ""))
                        .then(Commands.argument("keyword", StringArgumentType.greedyString())
                            .executes(ctx -> listBag(ctx.getSource(),
                                StringArgumentType.getString(ctx, "keyword"))))))

                // /rsupgrade auto …  —— 自动模式（支持按玩家 / 按网络）
                .then(Commands.literal("auto")
                    .executes(ctx -> autoStatus(ctx.getSource()))

                    // 全局
                    .then(Commands.literal("all")
                        .then(Commands.literal("on").executes(ctx -> autoAll(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> autoAll(ctx.getSource(), false))))

                    // 按玩家
                    .then(Commands.literal("bag")
                        .executes(ctx -> autoBag(ctx.getSource(), null, true))
                        .then(Commands.literal("on")
                            .executes(ctx -> autoBag(ctx.getSource(), null, true))
                            .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> autoBag(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "player"), true))))
                        .then(Commands.literal("off")
                            .executes(ctx -> autoBag(ctx.getSource(), null, false))
                            .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> autoBag(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "player"), false)))))

                    // 按网络
                    .then(Commands.literal("rs")
                        .executes(ctx -> autoRsNearest(ctx.getSource(), true))
                        .then(Commands.literal("on").executes(ctx -> autoRsNearest(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> autoRsNearest(ctx.getSource(), false)))
                        .then(Commands.literal("list").executes(ctx -> autoRsList(ctx.getSource())))))

                // /rsupgrade debug  —— 打印背包里每个物品的结构，排错用
                .then(Commands.literal("debug")
                    .executes(ctx -> debugBag(ctx.getSource())))

                // /rsupgrade box <x> <y> <z> [关键字]  —— 处理箱子里的背包/升级
                .then(Commands.literal("box")
                    .then(Commands.argument("x", IntegerArgumentType.integer(-30000000, 30000000))
                        .then(Commands.argument("y", IntegerArgumentType.integer(-512, 512))
                            .then(Commands.argument("z", IntegerArgumentType.integer(-30000000, 30000000))
                                .executes(ctx -> fromContainer(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z"), ""))
                                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                    .executes(ctx -> fromContainer(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "y"),
                                        IntegerArgumentType.getInteger(ctx, "z"),
                                        StringArgumentType.getString(ctx, "keyword"))))))))

                // /rsupgrade import <x> <y> <z>  —— 容器内容搬进 RS（调试/实用）
                .then(Commands.literal("import")
                    .then(Commands.argument("x", IntegerArgumentType.integer(-30000000, 30000000))
                        .then(Commands.argument("y", IntegerArgumentType.integer(-512, 512))
                            .then(Commands.argument("z", IntegerArgumentType.integer(-30000000, 30000000))
                                .executes(ctx -> importContainer(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "y"),
                                    IntegerArgumentType.getInteger(ctx, "z")))))))
        );
    }

    private static int help(CommandSourceStack src) {
        src.sendSystemMessage(Component.literal("§6[rsupgrade] 从升级物品里取出夹带的东西"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade rs [关键字] [deep]   §f把 RS 网络里升级物品的内容取出来"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade bag [关键字] [deep]  §f把背包里升级物品的内容取出来"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade list rs|bag [关键字] §f只看不取"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade box <x> <y> <z>      §f处理箱子里的背包/升级"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade import <x> <y> <z>   §f容器内容搬进 RS"));
        src.sendSystemMessage(Component.literal("§8默认只取一层；加 deep 会连嵌套的同类一起处理"));
        src.sendSystemMessage(Component.literal("§8带 * 的需要 OP；bag 系列普通玩家也能用"));
        return 1;
    }

    // ---------- 参数解析 ----------
    private static int parseRsArgs(CommandSourceStack src, String args) {
        Args a = Args.parse(args);
        return fromRs(src, a.keyword, a.deep);
    }

    private static int parseBagArgs(CommandSourceStack src, String args) {
        Args a = Args.parse(args);
        return fromBag(src, a.keyword, a.deep);
    }

    private record Args(String keyword, boolean deep, int limit) {
        static Args parse(String raw) {
            String s = raw == null ? "" : raw.trim();
            boolean deep = false;
            String kw = s;
            if (s.toLowerCase().endsWith(" deep")) {
                deep = true;
                kw = s.substring(0, s.length() - 5).trim();
            } else if (s.equalsIgnoreCase("deep")) {
                deep = true;
                kw = "";
            }
            return new Args(kw, deep, MAX_ITEMS_PER_COMMAND);
        }
    }

    // =========================================================
    //  NBT 小工具
    // =========================================================

    /** 这个 itemId 是否命中关键字（空关键字 = 全部） */
    private static boolean match(String id, String keyword) {
        return keyword == null || keyword.isEmpty()
            || id.toLowerCase().contains(keyword.toLowerCase());
    }

    /** 堆栈自身或它的某个子标签里，是否有非空的 Items 列表 */
    // =========================================================
    //  NBT：从"升级物品"里取东西（支持背包 → 升级 → 升级内部）
    // =========================================================

    /** 物品 ID 是不是"升级"类（精妙背包的 crafting_upgrade / jukebox_upgrade …） */
    private static boolean isUpgradeId(String id) {
        return id != null && id.toLowerCase().contains("upgrade");
    }

    /** 一个 CompoundTag 里是不是有非空的 Items 列表 */
    private static boolean tagHasItems(CompoundTag tag) {
        if (tag == null) return false;
        return tag.contains("Items", 9) && !tag.getList("Items", 10).isEmpty();
    }

    /**
     * 宿主物品里是否有"可提取"的升级内容：
     *   1) 自己就是升级物品（crafting_upgrade 等）→ 看它自己的 Items
     *   2) 自己是背包之类 → 看它升级槽里的升级物品，看那些升级里的 Items
     */
    private static boolean hasExtractable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;

        // 情况 1：自己就是升级
        if (isUpgradeId(id)) {
            for (String key : tag.getAllKeys()) {
                if (tag.get(key) instanceof CompoundTag inner && tagHasItems(inner)) {
                    return true;
                }
            }
        }

        // 情况 0：精妙背包 —— 走它的 API（升级槽里的内容不一定在 NBT 里）
        if (SbCompat.available() && SbCompat.isBackpack(stack)) {
            for (var h : SbCompat.upgradeInventoriesOf(stack)) {
                for (int i = 0; i < h.getSlots(); i++) {
                    if (!h.getStackInSlot(i).isEmpty()) return true;
                }
            }
            var st = SbCompat.storageOf(stack);
            if (st != null) {
                for (int i = 0; i < st.getSlots(); i++) {
                    ItemStack s = st.getStackInSlot(i);
                    if (s.isEmpty()) continue;
                    if (!isUpgradeId(idOf(s))) continue;
                    if (countTagItems(s.getTag()) > 0) return true;
                }
            }
        }

        // 情况 2：背包的升级槽里有升级，且升级里有东西
        for (String key : tag.getAllKeys()) {
            if (!(tag.get(key) instanceof CompoundTag inv)) continue;
            if (!inv.contains("Items", 9)) continue;
            ListTag list = inv.getList("Items", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!isUpgradeId(entry.getString("id"))) continue;
                if (!entry.contains("tag", 10)) continue;
                CompoundTag upTag = entry.getCompound("tag");
                for (String k2 : upTag.getAllKeys()) {
                    if (upTag.get(k2) instanceof CompoundTag inner2 && tagHasItems(inner2)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 统计宿主里可提取的件数（含背包升级槽里的） */
    private static int countExtractable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        CompoundTag tag = stack.getTag();
        if (tag == null) return 0;

        int n = 0;

        // 精妙背包：走 API 统计
        if (SbCompat.available() && SbCompat.isBackpack(stack)) {
            for (var h : SbCompat.upgradeInventoriesOf(stack)) {
                for (int i = 0; i < h.getSlots(); i++) {
                    ItemStack it = h.getStackInSlot(i);
                    if (!it.isEmpty()) n += it.getCount();
                }
            }
            var st = SbCompat.storageOf(stack);
            if (st != null) {
                for (int i = 0; i < st.getSlots(); i++) {
                    ItemStack it = st.getStackInSlot(i);
                    if (it.isEmpty() || !isUpgradeId(idOf(it))) continue;
                    n += countTagItems(it.getTag());
                }
            }
        }

        if (isUpgradeId(id)) {
            n += countTagItems(tag);
        }
        for (String key : tag.getAllKeys()) {
            if (!(tag.get(key) instanceof CompoundTag inv) || !inv.contains("Items", 9)) continue;
            ListTag list = inv.getList("Items", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!isUpgradeId(entry.getString("id"))) continue;
                if (!entry.contains("tag", 10)) continue;
                n += countTagItems(entry.getCompound("tag"));
            }
        }
        return n;
    }

    private static int countTagItems(CompoundTag tag) {
        int n = 0;
        for (String key : tag.getAllKeys()) {
            if (tag.get(key) instanceof CompoundTag inner && inner.contains("Items", 9)) {
                ListTag items = inner.getList("Items", 10);
                for (int i = 0; i < items.size(); i++) {
                    n += items.getCompound(i).getByte("Count");
                }
            }
        }
        return n;
    }

    /**
     * 从宿主物品里把升级夹带的东西搬进网络。
     *
     * 只处理"升级物品内部"的 Items：
     *   - 宿主自己是升级 → 直接看它的子标签
     *   - 宿主是背包 → 下钻到它里面所有的**升级物品**（升级槽里的、主存储里的都算）\n     *     普通物品（非 upgrade）永远不会被搬走
     */
    private static int drainHost(INetwork net, ItemStack host, String filterKeyword, int remaining) {
        if (host == null || host.isEmpty() || remaining <= 0) return 0;
        String id = BuiltInRegistries.ITEM.getKey(host.getItem()).toString();
        CompoundTag tag = host.getTag();
        if (tag == null) return 0;

        int moved = 0;

        // --- 情况 0：精妙背包 → 走 API ---
        if (SbCompat.available() && SbCompat.isBackpack(host)) {
            for (var h : SbCompat.upgradeInventoriesOf(host)) {
                moved += drainHandler(net, h, filterKeyword, remaining - moved);
                if (moved >= remaining) break;
            }
            var st = SbCompat.storageOf(host);
            if (st != null) {
                for (int i = 0; i < st.getSlots() && moved < remaining; i++) {
                    moved += drainStoredUpgrade(net, st, i, filterKeyword, remaining - moved);
                }
            }
            if (moved >= remaining) return moved;
        }

        // --- 情况 1：自己就是升级 ---
        if (isUpgradeId(id)) {
            moved += drainTag(net, tag, filterKeyword, remaining - moved);
        }

        // --- 情况 2：背包升级槽里的升级 ---
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (!(tag.get(key) instanceof CompoundTag inv)) continue;
            if (!inv.contains("Items", 9)) continue;

            ListTag list = inv.getList("Items", 10);
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                String entryId = entry.getString("id");
                if (!entry.contains("tag", 10)) continue;

                CompoundTag upTag = entry.getCompound("tag");

                // 升级物品 → 取它内部的东西
                // 背包物品 → 再往里钻一层（允许 背包→背包→升级）
                int m;
                if (isUpgradeId(entryId)) {
                    m = drainTag(net, upTag, filterKeyword, remaining - moved);
                } else if (entryId.toLowerCase().contains("backpack")) {
                    m = drainNested(net, upTag, filterKeyword, remaining - moved, 2);
                } else {
                    continue;
                }
                if (m > 0) {
                    entry.put("tag", upTag);
                    list.set(i, entry);
                    changed = true;
                    moved += m;
                }
                if (moved >= remaining) break;
            }
            if (changed) {
                inv.put("Items", list);
            }
            if (moved >= remaining) break;
        }
        return moved;
    }

    /**
     * 深层扫描：在 tag 里找所有的 Items 列表，对其中「升级」条目取内容，
     * 对其中「背包」条目再递归一层（depth 限制）。
     * 普通物品条目一律跳过。
     */
    private static int drainNested(INetwork net, CompoundTag tag, String filterKeyword,
                                   int remaining, int depth) {
        if (tag == null || remaining <= 0 || depth <= 0) return 0;
        int moved = 0;
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (!(tag.get(key) instanceof CompoundTag inv)) continue;
            if (!inv.contains("Items", 9)) continue;

            ListTag list = inv.getList("Items", 10);
            boolean changed = false;
            for (int i = 0; i < list.size() && moved < remaining; i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.contains("tag", 10)) continue;
                String entryId = entry.getString("id");
                CompoundTag upTag = entry.getCompound("tag");

                int m = 0;
                if (isUpgradeId(entryId)) {
                    m = drainTag(net, upTag, filterKeyword, remaining - moved);
                } else if (entryId.toLowerCase().contains("backpack")) {
                    m = drainNested(net, upTag, filterKeyword, remaining - moved, depth - 1);
                }
                if (m > 0) {
                    entry.put("tag", upTag);
                    list.set(i, entry);
                    changed = true;
                    moved += m;
                }
            }
            if (changed) {
                inv.put("Items", list);
            }
            if (moved >= remaining) break;
        }
        return moved;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * 处理"背包库存里的一个升级物品"。
     *
     * 关键点：SB 的库存 handler 可能返回**副本**，直接改副本不生效。
     * 所以流程是：在副本上扣掉条目 → 写回槽位 → 再真正插入网络。
     * 这样即使写回失败也不会凭空多出物品。
     */
    private static int drainStoredUpgrade(INetwork net, net.minecraftforge.items.IItemHandler handler,
                                          int slot, String filterKeyword, int remaining) {
        ItemStack stack = handler.getStackInSlot(slot);
        if (stack == null || stack.isEmpty()) return 0;
        if (!isUpgradeId(idOf(stack))) return 0;

        CompoundTag tag = stack.getTag();
        if (tag == null) return 0;

        // 先在 tag 上把能取的条目摘下来（模拟插入成功才摘）
        List<ItemStack> toInsert = new ArrayList<>();
        int planned = detachItems(net, tag, filterKeyword, remaining, toInsert);
        if (planned <= 0) return 0;

        // 写回槽位：多重尝试 + 校验，确保改动真的落盘
        boolean written = writeBack(handler, slot, stack);
        if (!written) {
            LOGGER.info("[rsupgrade] 背包库存写回失败（handler={}），本件跳过",
                handler.getClass().getName());
            return 0;
        }

        // 真正放进网络
        int moved = 0;
        for (ItemStack s : toInsert) {
            net.insertItem(s, s.getCount(), Action.PERFORM);
            moved += s.getCount();
        }
        return moved;
    }

    /**
     * 把修改后的 stack 写回 handler 的第 slot 格，并校验是否真的生效。
     * 依次尝试：IItemHandlerModifiable → 反射 setStackInSlot → 直接改活对象的 tag。
     */
    private static boolean writeBack(net.minecraftforge.items.IItemHandler handler,
                                     int slot, ItemStack wanted) {
        String wantTag = wanted.getTag() == null ? "" : wanted.getTag().toString();

        // 0) 如果 getStackInSlot 返回的就是活对象，它的 tag 可能已经被我们改过
        try {
            ItemStack live = handler.getStackInSlot(slot);
            if (live != null && !live.isEmpty()) {
                String liveTag = live.getTag() == null ? "" : live.getTag().toString();
                if (liveTag.equals(wantTag)) return true;
            }
        } catch (Throwable ignored) {
        }

        // 1) 标准接口
        try {
            if (handler instanceof net.minecraftforge.items.IItemHandlerModifiable mod) {
                mod.setStackInSlot(slot, wanted.copy());
            }
        } catch (Throwable ignored) {
        }
        if (verify(handler, slot, wantTag)) return true;

        // 2) 反射调用 setStackInSlot
        try {
            var m = handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);
            m.invoke(handler, slot, wanted.copy());
        } catch (Throwable ignored) {
        }
        if (verify(handler, slot, wantTag)) return true;

        // 3) 直接改活对象的 tag
        try {
            ItemStack live = handler.getStackInSlot(slot);
            if (live != null && !live.isEmpty()) {
                live.setTag(wanted.getTag() == null ? null : wanted.getTag().copy());
            }
        } catch (Throwable ignored) {
        }
        return verify(handler, slot, wantTag);
    }

    private static boolean verify(net.minecraftforge.items.IItemHandler handler,
                                  int slot, String wantTag) {
        try {
            ItemStack now = handler.getStackInSlot(slot);
            String nowTag = now == null || now.getTag() == null ? "" : now.getTag().toString();
            return nowTag.equals(wantTag);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从 tag 里摘出可放入网络的物品（模拟插入成功后摘除），
     * 摘下来的放到 out 里，返回计划搬运的件数。
     */
    private static int detachItems(INetwork net, CompoundTag tag, String filterKeyword,
                                   int remaining, List<ItemStack> out) {
        if (tag == null || remaining <= 0) return 0;
        int planned = 0;

        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (!(tag.get(key) instanceof CompoundTag inner)) continue;
            if (!inner.contains("Items", 9)) continue;

            ListTag items = inner.getList("Items", 10);
            boolean changed = false;

            for (int i = items.size() - 1; i >= 0 && planned < remaining; i--) {
                CompoundTag entry = items.getCompound(i);
                ItemStack s = ItemStack.of(entry);
                if (s.isEmpty()) continue;

                String id = idOf(s);
                if (!filterKeyword.isEmpty() && !match(id, filterKeyword)) continue;

                int want = Math.min(remaining - planned, s.getCount());
                ItemStack sim = net.insertItem(s.copyWithCount(want), want, Action.SIMULATE);
                int accepted = want - (sim.isEmpty() ? 0 : sim.getCount());
                if (accepted <= 0) continue;

                out.add(s.copyWithCount(accepted));
                planned += accepted;
                changed = true;

                if (accepted >= s.getCount()) {
                    items.remove(i);
                } else {
                    entry.putByte("Count", (byte) (s.getCount() - accepted));
                    items.set(i, entry);
                }
            }
            if (changed) {
                inner.put("Items", items);
            }
        }
        return planned;
    }

    /** 从 IItemHandler 里把东西搬进网络（先 SIMULATE 再真取） */
    private static int drainHandler(INetwork net, net.minecraftforge.items.IItemHandler h,
                                    String filterKeyword, int remaining) {
        if (h == null || remaining <= 0) return 0;
        int moved = 0;
        for (int i = 0; i < h.getSlots() && moved < remaining; i++) {
            ItemStack s = h.getStackInSlot(i);
            if (s == null || s.isEmpty()) continue;
            String id = idOf(s);
            if (!filterKeyword.isEmpty() && !match(id, filterKeyword)) continue;

            int want = Math.min(remaining - moved, s.getCount());
            ItemStack sim = net.insertItem(s.copyWithCount(want), want, Action.SIMULATE);
            int accepted = want - (sim.isEmpty() ? 0 : sim.getCount());
            if (accepted <= 0) continue;

            ItemStack taken = h.extractItem(i, accepted, false);
            if (taken == null || taken.isEmpty()) continue;
            net.insertItem(taken.copy(), taken.getCount(), Action.PERFORM);
            moved += taken.getCount();
        }
        return moved;
    }

    /** 把一个 tag 里所有内嵌 Items 列表的内容搬进网络 */
    private static int drainTag(INetwork net, CompoundTag tag, String filterKeyword, int remaining) {
        if (tag == null || remaining <= 0) return 0;
        int moved = 0;
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (!(tag.get(key) instanceof CompoundTag inner)) continue;
            if (!inner.contains("Items", 9)) continue;

            ListTag items = inner.getList("Items", 10);
            boolean changed = false;

            for (int i = items.size() - 1; i >= 0 && moved < remaining; i--) {
                CompoundTag entry = items.getCompound(i);
                ItemStack stackInSlot = ItemStack.of(entry);
                if (stackInSlot.isEmpty()) continue;

                String id = BuiltInRegistries.ITEM.getKey(stackInSlot.getItem()).toString();
                if (!filterKeyword.isEmpty() && !match(id, filterKeyword)) continue;

                int want = Math.min(remaining - moved, stackInSlot.getCount());

                ItemStack simulated = net.insertItem(stackInSlot.copyWithCount(want), want, Action.SIMULATE);
                int accepted = want - (simulated.isEmpty() ? 0 : simulated.getCount());
                if (accepted <= 0) continue;

                net.insertItem(stackInSlot.copyWithCount(accepted), accepted, Action.PERFORM);
                moved += accepted;
                changed = true;

                if (accepted >= stackInSlot.getCount()) {
                    items.remove(i);
                } else {
                    entry.putByte("Count", (byte) (stackInSlot.getCount() - accepted));
                    items.set(i, entry);
                }
            }
            if (changed) {
                inner.put("Items", items);
            }
        }
        return moved;
    }


    // =========================================================
    //  RS 网络定位
    // =========================================================

    private static INetwork networkNear(ServerLevel level, BlockPos ref) {
        INetwork best = null;
        double bestDist = Double.MAX_VALUE;
        for (INetwork n : API.instance().getNetworkManager(level).all()) {
            if (n == null) continue;
            double d = n.getPosition().distSqr(ref);
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    private static INetwork networkFor(CommandSourceStack src) {
        return networkNear(src.getLevel(), BlockPos.containing(src.getPosition()));
    }

    private static List<ItemStack> collectRsCandidates(INetwork net, String keyword) {
        List<ItemStack> out = new ArrayList<>();
        for (StackListEntry<ItemStack> e : net.getItemStorageCache().getList().getStacks()) {
            ItemStack s = e.getStack();
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (!match(id, keyword)) continue;
            if (hasExtractable(s)) out.add(s);
        }
        return out;
    }

    private static int fromRs(CommandSourceStack src, String keyword, boolean deep) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("[rsupgrade] 从 RS 网络提取需要 OP"));
            return 0;
        }
        INetwork net = networkFor(src);
        if (net == null) {
            src.sendFailure(Component.literal("[rsupgrade] 附近没有 RS 网络"));
            return 0;
        }

        int total = 0;
        int rounds = 0;
        int maxRounds = deep ? 64 : 1;

        while (rounds < maxRounds) {
            List<ItemStack> candidates = collectRsCandidates(net, keyword);
            if (candidates.isEmpty()) break;

            int movedThisRound = 0;
            int processed = 0;

            for (ItemStack candidate : candidates) {
                if (processed >= MAX_ITEMS_PER_COMMAND) break;
                int count = candidate.getCount();
                for (int c = 0; c < count && processed < MAX_ITEMS_PER_COMMAND; c++) {
                    ItemStack one = candidate.copyWithCount(1);
                    ItemStack real = net.extractItem(one, 1, Action.PERFORM);
                    if (real == null || real.isEmpty()) break;

                    int moved = drainHost(net, real, keyword, MAX_ITEMS_PER_COMMAND - total);
                    total += moved;
                    movedThisRound += moved;
                    processed++;

                    // 放回去（此时它的内层已经被清空一层）
                    ItemStack back = net.insertItem(real, 1, Action.PERFORM);
                    if (!back.isEmpty()) {
                        LOGGER.warn("[rsupgrade] 物品放回失败: {}", back.getItem());
                    }
                }
            }

            rounds++;
            if (movedThisRound == 0) break;
            if (!deep) break;
        }

        int remaining = collectRsCandidates(net, keyword).size();
        final int t = total;
        final int r = remaining;
        src.sendSuccess(() -> Component.literal(
            "[rsupgrade] 从 RS 取出 " + t + " 件"
            + (r > 0 ? "，还有 " + r + " 类物品没处理完（可再执行一次）" : "")), false);
        LOGGER.info("[rsupgrade] rs 取出 {} 件，剩余 {} 类", t, r);
        return total;
    }

    private static int listRs(CommandSourceStack src, String keyword) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("[rsupgrade] 查看网络内容需要 OP"));
            return 0;
        }
        INetwork net = networkFor(src);
        if (net == null) {
            src.sendFailure(Component.literal("[rsupgrade] 附近没有 RS 网络"));
            return 0;
        }
        List<ItemStack> candidates = collectRsCandidates(net, keyword);
        if (candidates.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[rsupgrade] 网络里没有夹带物品的升级"), false);
            return 0;
        }
        int totalItems = 0;
        for (ItemStack c : candidates) {
            int inner = countExtractable(c);
            totalItems += inner * c.getCount();
            String id = BuiltInRegistries.ITEM.getKey(c.getItem()).toString();
            src.sendSystemMessage(Component.literal(
                "§7- §f" + id + " §7x" + c.getCount() + " §7内部共 " + (inner * c.getCount()) + " 件"));
        }
        final int n = candidates.size();
        final int ti = totalItems;
        src.sendSuccess(() -> Component.literal(
            "[rsupgrade] " + n + " 类、合计 " + ti + " 件可取出"), false);
        return n;
    }

    // =========================================================
    //  玩家背包
    // =========================================================

    /** 一个可写回的槽位：背包格子、副手、或 Curios 槽 */
    private record Holder(ItemStack stack, java.util.function.Consumer<ItemStack> commit) {
    }

    /** 收集玩家身上所有"夹带东西"的升级（主背包 + 副手 + Curios 槽） */
    private static List<Holder> collectBagHolders(ServerPlayer player, String keyword) {
        List<Holder> out = new ArrayList<>();
        java.util.Set<ItemStack> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (ItemStack s : player.getInventory().items) {
            if (s == null || s.isEmpty() || !seen.add(s)) continue;
            if (!matches(s, keyword)) continue;
            out.add(new Holder(s, x -> { }));
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (s == null || s.isEmpty() || !seen.add(s)) continue;
            if (!matches(s, keyword)) continue;
            out.add(new Holder(s, x -> { }));
        }

        // 盔甲栏位：精妙背包可以穿在胸甲位
        for (ItemStack s : player.getInventory().armor) {
            if (s == null || s.isEmpty() || !seen.add(s)) continue;
            if (!matches(s, keyword)) continue;
            out.add(new Holder(s, x -> { }));
        }

        if (CuriosCompat.available()) {
            for (CuriosCompat.Slot slot : CuriosCompat.slots(player)) {
                ItemStack s;
                try {
                    s = slot.stack();
                } catch (Throwable t) {
                    continue;
                }
                if (s == null || s.isEmpty() || !seen.add(s)) continue;
                if (!matches(s, keyword)) continue;
                out.add(new Holder(s, slot::commit));
            }
        }
        return out;
    }

    private static boolean matches(ItemStack stack, String keyword) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!match(id, keyword)) return false;
        return hasExtractable(stack);
    }

    private static int fromBag(CommandSourceStack src, String keyword, boolean deep) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这条指令需要玩家执行（背包属于玩家）"));
            return 0;
        }
        INetwork net = networkFor(src);
        if (net == null) {
            src.sendFailure(Component.literal("[rsupgrade] 附近没有 RS 网络"));
            return 0;
        }

        int total = 0;
        int rounds = 0;
        int maxRounds = deep ? 8 : 1;

        while (rounds < maxRounds) {
            List<Holder> holders = collectBagHolders(player, keyword);
            if (holders.isEmpty()) break;

            int movedThisRound = 0;
            for (Holder h : holders) {
                int moved = drainHost(net, h.stack(), keyword, MAX_ITEMS_PER_COMMAND - total);
                if (moved > 0) {
                    h.commit().accept(h.stack());
                }
                total += moved;
                movedThisRound += moved;
                if (total >= MAX_ITEMS_PER_COMMAND) break;
            }
            rounds++;
            if (movedThisRound == 0) break;
            if (!deep) break;
            if (rounds == 1) {
                fromRs(src, keyword, true);
            }
        }

        // 同步背包 / Curios 变化给客户端
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();

        int remaining = collectBagHolders(player, keyword).size();
        final int t = total;
        final int r = remaining;
        final boolean curiosIncluded = CuriosCompat.available();
        src.sendSuccess(() -> Component.literal(
            "[rsupgrade] 从背包取出 " + t + " 件"
            + (curiosIncluded ? "（含 Curios 槽）" : "")
            + (r > 0 ? "，还有 " + r + " 个没处理完" : "")), false);
        LOGGER.info("[rsupgrade] bag 取出 {} 件，剩余 {} 个（curios={}）", t, r, curiosIncluded);
        return total;
    }

    private static int listBag(CommandSourceStack src, String keyword) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这条指令需要玩家执行"));
            return 0;
        }
        List<Holder> candidates = collectBagHolders(player, keyword);
        if (candidates.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[rsupgrade] 背包里没有夹带物品的升级"), false);
            return 0;
        }
        int totalItems = 0;
        for (Holder h : candidates) {
            int inner = countExtractable(h.stack());
            totalItems += inner;
            String id = BuiltInRegistries.ITEM.getKey(h.stack().getItem()).toString();
            src.sendSystemMessage(Component.literal(
                "§7- §f" + id + " §7内部 " + inner + " 件"));
        }
        final int n = candidates.size();
        final int ti = totalItems;
        src.sendSuccess(() -> Component.literal(
            "[rsupgrade] " + n + " 个物品、合计 " + ti + " 件可取出"), false);
        return n;
    }

    // =========================================================
    //  容器 → RS（实用小工具，也方便测试）
    // =========================================================
    private static int importContainer(CommandSourceStack src, int x, int y, int z) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("[rsupgrade] 搬运容器需要 OP"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        var be = level.getBlockEntity(new BlockPos(x, y, z));
        if (be == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这个坐标没有方块实体"));
            return 0;
        }
        var handler = be.getCapability(
            net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (handler == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这个方块没有物品栏"));
            return 0;
        }
        INetwork net = networkNear(level, new BlockPos(x, y, z));
        if (net == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这里没有 RS 网络"));
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty()) continue;
            ItemStack sim = net.insertItem(s.copy(), s.getCount(), Action.SIMULATE);
            int accepted = s.getCount() - (sim.isEmpty() ? 0 : sim.getCount());
            if (accepted <= 0) continue;
            ItemStack taken = handler.extractItem(i, accepted, false);
            if (taken.isEmpty()) continue;
            net.insertItem(taken.copy(), taken.getCount(), Action.PERFORM);
            moved += taken.getCount();
        }
        final int m = moved;
        src.sendSuccess(() -> Component.literal("[rsupgrade] 容器内容已搬进 RS：" + m + " 件"), false);
        return moved;
    }

    // =========================================================
    //  箱子 → 里面的背包/升级（不移动物品本身，只取升级内容）
    // =========================================================
    // =========================================================
    //  调试：打印背包里每个物品的结构
    // =========================================================
    private static int debugBag(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[rsupgrade] 需要玩家执行"));
            return 0;
        }
        src.sendSystemMessage(Component.literal("§6[rsupgrade] 背包诊断"));
        src.sendSystemMessage(Component.literal("§7SB=" + SbCompat.available()
            + " Curios=" + CuriosCompat.available()));

        int idx = 0;
        for (ItemStack s : player.getInventory().items) {
            idx++;
            if (s.isEmpty()) continue;
            dumpStack(src, idx, s);
        }
        for (ItemStack s : player.getInventory().offhand) {
            idx++;
            if (s.isEmpty()) continue;
            dumpStack(src, idx, s);
        }
        if (CuriosCompat.available()) {
            for (CuriosCompat.Slot slot : CuriosCompat.slots(player)) {
                ItemStack s;
                try {
                    s = slot.stack();
                } catch (Throwable t) {
                    continue;
                }
                if (s == null || s.isEmpty()) continue;
                src.sendSystemMessage(Component.literal("§8[curios " + slot.index() + "]"));
                dumpStack(src, -1, s);
            }
        }
        return 1;
    }

    private static void dumpStack(CommandSourceStack src, int slot, ItemStack s) {
        String id = idOf(s);
        CompoundTag tag = s.getTag();
        boolean backpack = SbCompat.available() && SbCompat.isBackpack(s);
        boolean extractable = hasExtractable(s);

        StringBuilder sb = new StringBuilder();
        sb.append(slot >= 0 ? "§7[" + slot + "] " : "");
        sb.append("§f").append(id);
        sb.append(" §7x").append(s.getCount());
        if (backpack) sb.append(" §b[背包]");
        if (isUpgradeId(id)) sb.append(" §d[升级]");
        sb.append(extractable ? " §a✔有货" : " §8无可取");
        src.sendSystemMessage(Component.literal(sb.toString()));

        if (tag != null) {
            src.sendSystemMessage(Component.literal("    §8NBT: " + tag.getAllKeys()));
        } else {
            src.sendSystemMessage(Component.literal("    §8NBT: 无"));
        }
        if (backpack) {
            var ups = SbCompat.upgradeIds(s);
            src.sendSystemMessage(Component.literal("    §8升级槽: " + ups));
            var st = SbCompat.storageOf(s);
            if (st == null) {
                src.sendSystemMessage(Component.literal("    §c库存读取失败"));
            } else {
                int upsInStore = 0;
                int innerInStore = 0;
                for (int i = 0; i < st.getSlots(); i++) {
                    ItemStack it = st.getStackInSlot(i);
                    if (it.isEmpty()) continue;
                    if (!isUpgradeId(idOf(it))) continue;
                    upsInStore++;
                    innerInStore += countTagItems(it.getTag());
                }
                src.sendSystemMessage(Component.literal(
                    "    §8库存: " + st.getSlots() + " 格，其中升级物品 " + upsInStore
                    + " 个，内部共 " + innerInStore + " 件（handler="
                    + st.getClass().getSimpleName() + "）"));
            }
            int invCount = 0;
            for (var h : SbCompat.upgradeInventoriesOf(s)) {
                for (int i = 0; i < h.getSlots(); i++) {
                    if (!h.getStackInSlot(i).isEmpty()) invCount++;
                }
            }
            src.sendSystemMessage(Component.literal("    §8升级内物品: " + invCount + " 件"));
        }
    }

    private static int fromContainer(CommandSourceStack src, int x, int y, int z, String keyword) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("[rsupgrade] 处理容器需要 OP"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        var be = level.getBlockEntity(new BlockPos(x, y, z));
        if (be == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这个坐标没有方块实体"));
            return 0;
        }
        var handler = be.getCapability(
            net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (handler == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这个方块没有物品栏"));
            return 0;
        }
        INetwork net = networkNear(level, new BlockPos(x, y, z));
        if (net == null) {
            src.sendFailure(Component.literal("[rsupgrade] 这里没有 RS 网络"));
            return 0;
        }

        String kw = keyword == null ? "" : keyword.trim();
        int moved = 0;
        int touched = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (!match(id, kw)) continue;
            if (!hasExtractable(s)) continue;

            int m = drainHost(net, s, kw, MAX_ITEMS_PER_COMMAND - moved);
            if (m > 0) {
                handler.extractItem(i, 0, false);   // 触发一次写回（数量为 0）
                handler.insertItem(i, s, false);
                touched++;
                moved += m;
            }
            if (moved >= MAX_ITEMS_PER_COMMAND) break;
        }
        be.setChanged();
        level.sendBlockUpdated(new BlockPos(x, y, z), be.getBlockState(), be.getBlockState(), 3);

        final int m2 = moved;
        final int t2 = touched;
        src.sendSuccess(() -> Component.literal(
            "[rsupgrade] 容器里处理了 " + t2 + " 个物品，取出 " + m2 + " 件"), false);
        LOGGER.info("[rsupgrade] box {} {} {} 处理 {} 个，取出 {} 件", x, y, z, t2, m2);
        return moved;
    }

    // =========================================================
    //  玩家登录时打印一次提示（仅 op 可见，避免打扰普通玩家）
    // =========================================================
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && sp.hasPermissions(2)) {
            sp.sendSystemMessage(Component.literal(
                "§8[rsupgrade] 已就绪：/rsupgrade 查看用法"));
        }
    }

    /** 供其它地方复用（当前未使用） */
    public static Map<String, Integer> summarize(List<ItemStack> stacks) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : stacks) {
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            m.merge(id, s.getCount(), Integer::sum);
        }
        return m;
    }

    // =========================================================
    //  自动模式（按玩家 / 按网络）
    // =========================================================

    /** 自动模式的检查间隔（tick），200 = 10 秒 */
    private static final int AUTO_INTERVAL_TICKS = 200;
    /** 自动模式每次最多处理多少件 */
    private static final int AUTO_MAX_PER_PASS = 256;

    private static int autoStatus(CommandSourceStack src) {
        AutoState st = AutoState.get(src.getLevel());
        src.sendSystemMessage(Component.literal("§6[rsupgrade] 自动模式：" + st.describe()));
        src.sendSystemMessage(Component.literal("§7/rsupgrade auto all on|off        §f所有玩家 + 所有网络"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade auto bag on|off [玩家] §f只对某个玩家（不填=自己）"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade auto rs  on|off        §f只对最近的网络"));
        src.sendSystemMessage(Component.literal("§7/rsupgrade auto rs  list          §f看已开启的网络"));
        return 1;
    }

    private static int autoAll(CommandSourceStack src, boolean value) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("[rsupgrade] 需要 OP"));
            return 0;
        }
        AutoState st = AutoState.get(src.getLevel());
        st.setAllBag(value);
        st.setAllRs(value);
        final String desc = st.describe();
        src.sendSuccess(() -> Component.literal("§a[rsupgrade] 全局自动 → " + desc), false);
        LOGGER.info("[rsupgrade] 全局自动改为 {}", desc);
        return 1;
    }

    private static int autoBag(CommandSourceStack src, String playerName, boolean value) {
        ServerPlayer target;
        boolean self;
        if (playerName == null || playerName.isBlank()) {
            target = src.getPlayer();
            self = true;
            if (target == null) {
                src.sendFailure(Component.literal("[rsupgrade] 控制台请指定玩家名"));
                return 0;
            }
        } else {
            target = src.getServer().getPlayerList().getPlayerByName(playerName);
            self = src.getPlayer() != null && src.getPlayer().getUUID().equals(target != null ? target.getUUID() : null);
            if (target == null) {
                src.sendFailure(Component.literal("[rsupgrade] 找不到在线玩家：" + playerName));
                return 0;
            }
        }
        if (!self && !src.hasPermission(2)) {
            src.sendFailure(Component.literal("[rsupgrade] 只能改自己的"));
            return 0;
        }
        AutoState st = AutoState.get(src.getLevel());
        st.setPlayerBag(target.getUUID(), value);
        final String name = target.getGameProfile().getName();
        final boolean v = value;
        src.sendSuccess(() -> Component.literal(
            "§a[rsupgrade] " + name + " 的背包自动 → " + (v ? "开" : "关")), false);
        LOGGER.info("[rsupgrade] 玩家 {} 背包自动 = {}", name, v);
        return 1;
    }

    private static int autoRsNearest(CommandSourceStack src, boolean value) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("[rsupgrade] 需要 OP"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        INetwork net = networkNear(level, BlockPos.containing(src.getPosition()));
        if (net == null) {
            src.sendFailure(Component.literal("[rsupgrade] 附近没有 RS 网络"));
            return 0;
        }
        BlockPos pos = net.getPosition();
        String key = AutoState.networkKey(level.dimension().location(), pos);
        AutoState st = AutoState.get(level);
        st.setNetwork(key, value);
        final String label = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        final boolean v = value;
        src.sendSuccess(() -> Component.literal(
            "§a[rsupgrade] 网络 " + label + " 自动 → " + (v ? "开" : "关")), false);
        LOGGER.info("[rsupgrade] 网络 {} 自动 = {}", key, v);
        return 1;
    }

    private static int autoRsList(CommandSourceStack src) {
        AutoState st = AutoState.get(src.getLevel());
        if (st.rsNetworks.isEmpty() && !st.allRs) {
            src.sendSuccess(() -> Component.literal("§7[rsupgrade] 还没有开启任何网络"), false);
            return 0;
        }
        if (st.allRs) {
            src.sendSystemMessage(Component.literal("§a- 全部网络"));
        }
        int i = 0;
        for (String key : st.rsNetworks) {
            i++;
            src.sendSystemMessage(Component.literal("§7- §f" + key));
        }
        final int n = i;
        src.sendSuccess(() -> Component.literal("[rsupgrade] 共 " + n + " 个单独开启的网络"), false);
        return n;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (server.getTickCount() % AUTO_INTERVAL_TICKS != 0) return;

        AutoState st;
        try {
            st = AutoState.get(server.overworld());
        } catch (Throwable t) {
            return;
        }
        if (!st.anyEnabled()) return;

        // ---- 背包：只处理"被打开自动"的玩家 ----
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (!st.isBagAuto(player.getUUID())) continue;
                int moved = runBagOnce(player, "", false, AUTO_MAX_PER_PASS, true);
                if (moved > 0) {
                    LOGGER.debug("[rsupgrade] 自动背包 {} 取出 {} 件",
                        player.getGameProfile().getName(), moved);
                }
            } catch (Throwable t) {
                LOGGER.debug("[rsupgrade] 自动背包处理失败: {}", t.toString());
            }
        }

        // ---- 网络：只处理"被打开自动"的网络 ----
        for (ServerLevel level : server.getAllLevels()) {
            try {
                for (INetwork net : API.instance().getNetworkManager(level).all()) {
                    if (net == null) continue;
                    String key = AutoState.networkKey(
                        level.dimension().location(), net.getPosition());
                    if (!st.isRsAuto(key)) continue;
                    int moved = runRsOnNetwork(net, "", AUTO_MAX_PER_PASS);
                    if (moved > 0) {
                        LOGGER.debug("[rsupgrade] 自动网络 {} 取出 {} 件", key, moved);
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("[rsupgrade] 自动网络遍历失败: {}", t.toString());
            }
        }

        st.lastRun = System.currentTimeMillis();
        st.setDirty();
    }

    /** 对指定网络跑一轮 */
    static int runRsOnNetwork(INetwork net, String keyword, int limit) {
        if (net == null) return 0;
        int total = 0;
        for (ItemStack candidate : collectRsCandidates(net, keyword)) {
            int count = candidate.getCount();
            for (int c = 0; c < count && total < limit; c++) {
                ItemStack one = candidate.copyWithCount(1);
                ItemStack real = net.extractItem(one, 1, Action.PERFORM);
                if (real == null || real.isEmpty()) break;
                total += drainHost(net, real, keyword, limit - total);
                ItemStack back = net.insertItem(real, 1, Action.PERFORM);
                if (!back.isEmpty()) {
                    LOGGER.warn("[rsupgrade] 放回失败: {}", back.getItem());
                }
            }
            if (total >= limit) break;
        }
        return total;
    }

    // =========================================================
    //  快捷键（客户端发包 → 服务端执行一次）
    // =========================================================

    private static final Map<java.util.UUID, Long> LAST_QUICK = new java.util.HashMap<>();

    public static void handleQuickExtract(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_QUICK.get(player.getUUID());
        if (last != null && now - last < 500L) return;
        LAST_QUICK.put(player.getUUID(), now);

        int bag = 0;
        int rs = 0;
        try {
            bag = runBagOnce(player, "", false, MAX_ITEMS_PER_COMMAND, false);
        } catch (Throwable t) {
            LOGGER.warn("[rsupgrade] 快捷提取(背包)失败: {}", t.toString());
        }
        try {
            rs = runRsOnce(player.serverLevel(), "", false, MAX_ITEMS_PER_COMMAND, false);
        } catch (Throwable t) {
            LOGGER.warn("[rsupgrade] 快捷提取(网络)失败: {}", t.toString());
        }

        final int b = bag;
        final int r = rs;
        if (b == 0 && r == 0) {
            player.displayClientMessage(Component.literal("§7[rsupgrade] 没有可提取的东西"), true);
        } else {
            player.displayClientMessage(Component.literal(
                "§a[rsupgrade] 背包 " + b + " 件，网络 " + r + " 件"), true);
        }
    }

    // =========================================================
    //  可复用的"跑一次"（指令 / 快捷键 / 自动 都调它）
    // =========================================================

    /** 对某个玩家背包跑一轮，返回件数。quiet=true 时不发消息 */
    static int runBagOnce(ServerPlayer player, String keyword, boolean deep, int limit, boolean quiet) {
        INetwork net = networkNear(player.serverLevel(),
            BlockPos.containing(player.position()));
        if (net == null) return 0;

        int total = 0;
        for (Holder h : collectBagHolders(player, keyword)) {
            int moved = drainHost(net, h.stack(), keyword, limit - total);
            if (moved > 0) {
                h.commit().accept(h.stack());
                total += moved;
            }
            if (total >= limit) break;
        }
        if (total > 0) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        return total;
    }

    /** 对某个维度的网络跑一轮，返回件数 */
    static int runRsOnce(ServerLevel level, String keyword, boolean deep, int limit, boolean quiet) {
        INetwork net = networkNear(level, BlockPos.ZERO);
        if (net == null) return 0;

        int total = 0;
        for (ItemStack candidate : collectRsCandidates(net, keyword)) {
            int count = candidate.getCount();
            for (int c = 0; c < count && total < limit; c++) {
                ItemStack one = candidate.copyWithCount(1);
                ItemStack real = net.extractItem(one, 1, Action.PERFORM);
                if (real == null || real.isEmpty()) break;
                total += drainHost(net, real, keyword, limit - total);
                ItemStack back = net.insertItem(real, 1, Action.PERFORM);
                if (!back.isEmpty()) {
                    LOGGER.warn("[rsupgrade] 放回失败: {}", back.getItem());
                }
            }
            if (total >= limit) break;
        }
        return total;
    }

}
