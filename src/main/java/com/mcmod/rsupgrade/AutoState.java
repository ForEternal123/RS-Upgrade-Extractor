package com.mcmod.rsupgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 自动模式开关，存在主世界存档里（world/data/rsupgrade_auto.dat）。
 *
 * 支持三种粒度：
 *   1. 全部玩家 / 全部网络      （allBag / allRs）
 *   2. 指定玩家                 （bagPlayers，按 UUID）
 *   3. 指定 RS 网络             （rsNetworks，按 "维度|坐标"）
 */
public class AutoState extends SavedData {

    private static final String DATA_NAME = "rsupgrade_auto";

    /** 全局：所有玩家 / 所有网络 */
    public boolean allBag = false;
    public boolean allRs = false;

    /** 指定玩家（UUID） */
    public final Set<UUID> bagPlayers = new LinkedHashSet<>();
    /** 指定网络（key = 维度|X,Y,Z） */
    public final Set<String> rsNetworks = new LinkedHashSet<>();

    public long lastRun = 0L;

    // ---------------- 存取 ----------------

    public static AutoState get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            AutoState::load, AutoState::new, DATA_NAME);
    }

    public static AutoState load(CompoundTag tag) {
        AutoState s = new AutoState();
        s.allBag = tag.getBoolean("allBag");
        s.allRs = tag.getBoolean("allRs");
        s.lastRun = tag.getLong("lastRun");

        ListTag pl = tag.getList("bagPlayers", Tag.TAG_STRING);
        for (int i = 0; i < pl.size(); i++) {
            try {
                s.bagPlayers.add(UUID.fromString(pl.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag nl = tag.getList("rsNetworks", Tag.TAG_STRING);
        for (int i = 0; i < nl.size(); i++) {
            s.rsNetworks.add(nl.getString(i));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("allBag", allBag);
        tag.putBoolean("allRs", allRs);
        tag.putLong("lastRun", lastRun);

        ListTag pl = new ListTag();
        for (UUID id : bagPlayers) {
            pl.add(StringTag.valueOf(id.toString()));
        }
        tag.put("bagPlayers", pl);

        ListTag nl = new ListTag();
        for (String key : rsNetworks) {
            nl.add(StringTag.valueOf(key));
        }
        tag.put("rsNetworks", nl);
        return tag;
    }

    // ---------------- 判断 ----------------

    public static String networkKey(ResourceLocation dimension, BlockPos pos) {
        return dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public boolean isBagAuto(UUID player) {
        return allBag || bagPlayers.contains(player);
    }

    public boolean isRsAuto(String key) {
        return allRs || rsNetworks.contains(key);
    }

    public boolean anyEnabled() {
        return allBag || allRs || !bagPlayers.isEmpty() || !rsNetworks.isEmpty();
    }

    // ---------------- 修改 ----------------

    public boolean setAllBag(boolean v) {
        if (allBag == v) return false;
        allBag = v;
        setDirty();
        return true;
    }

    public boolean setAllRs(boolean v) {
        if (allRs == v) return false;
        allRs = v;
        setDirty();
        return true;
    }

    public boolean setPlayerBag(UUID id, boolean v) {
        boolean changed = v ? bagPlayers.add(id) : bagPlayers.remove(id);
        if (changed) setDirty();
        return changed;
    }

    public boolean setNetwork(String key, boolean v) {
        boolean changed = v ? rsNetworks.add(key) : rsNetworks.remove(key);
        if (changed) setDirty();
        return changed;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(allBag ? "§a背包:全部 " : "§7背包:关 ");
        if (!bagPlayers.isEmpty()) {
            sb.append("§e(").append(bagPlayers.size()).append(" 名玩家) ");
        }
        sb.append(allRs ? "§aRS:全部" : "§7RS:关");
        if (!rsNetworks.isEmpty()) {
            sb.append(" §e(").append(rsNetworks.size()).append(" 个网络)");
        }
        return sb.toString();
    }
}
