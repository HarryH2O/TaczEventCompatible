package com.Harry_H2O.pa_module;

import net.slipcor.pvparena.config.Debugger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 枪击伤害记录管理器
 * <p>
 * 本类作为 Forge 端（TACZ_listener）与 Bukkit 端（PA_listener）之间的数据桥梁：
 * <ul>
 *   <li>Forge 端在 TACZ 子弹命中时调用 {@link #recordDamage} 存储映射</li>
 *   <li>Bukkit 端在伤害事件拦截时调用 {@link #getKillInfo} 读取映射</li>
 * </ul>
 * <p>
 * 数据流向：TACZ_listener → KillInfoManager → PA_listener
 * <p>
 * 使用 ConcurrentHashMap 保证线程安全，因为 Forge 事件和 Bukkit 事件运行在不同线程上。
 */
public class KillInfoManager {

    /**
     * 存储受害者 UUID → 击杀信息的映射
     */
    private static final Map<UUID, KillInfo> records = new ConcurrentHashMap<>();

    /**
     * 存储爆炸位置 → 攻击者信息的映射（用于榴弹等爆炸武器的范围伤害追踪）
     */
    private static final Map<String, ExplosionInfo> explosionRecords = new ConcurrentHashMap<>();

    /**
     * 记录过期时间（毫秒）
     */
    private static final long EXPIRY_MS = 5000;

    /**
     * 击杀信息记录类（仅存储攻击者 UUID，用于伤害重定向时查找攻击者）
     */
    public static class KillInfo {
        private final UUID attackerId;
        private final long timestamp;

        public KillInfo(UUID attackerId) {
            this.attackerId = attackerId;
            this.timestamp = System.currentTimeMillis();
        }

        public UUID getAttackerId() { return attackerId; }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > EXPIRY_MS;
        }
    }

    /**
     * 爆炸信息记录类（仅存储攻击者 UUID，用于爆炸伤害重定向时查找攻击者）
     */
    public static class ExplosionInfo {
        private final UUID attackerId;
        private final long timestamp;

        public ExplosionInfo(UUID attackerId) {
            this.attackerId = attackerId;
            this.timestamp = System.currentTimeMillis();
        }

        public UUID getAttackerId() { return attackerId; }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > EXPIRY_MS;
        }
    }

    /**
     * 记录一次枪击伤害映射
     */
    public static void recordDamage(UUID victimId, UUID attackerId) {
        records.put(victimId, new KillInfo(attackerId));
        Debugger.debug("[TaczEventCompatible] KillInfoManager: recorded damage mapping, victim={}, attacker={}",
                victimId, attackerId);
    }

    /**
     * 记录一次 TACZ 爆炸事件
     */
    public static void recordExplosion(String locationKey, UUID attackerId) {
        explosionRecords.put(locationKey, new ExplosionInfo(attackerId));
        Debugger.debug("[TaczEventCompatible] KillInfoManager: recorded explosion at {}, attacker={}",
                locationKey, attackerId);
    }

    /**
     * 查询指定受害者的击杀信息
     */
    public static KillInfo getKillInfo(UUID victimId) {
        KillInfo record = records.get(victimId);
        if (record == null) {
            return null;
        }
        if (record.isExpired()) {
            records.remove(victimId);
            return null;
        }
        return record;
    }

    /**
     * 查询指定受害者附近的 TACZ 爆炸攻击者信息
     */
    public static ExplosionInfo getNearbyExplosion(String worldName, int x, int y, int z) {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    String key = worldName + ":" + (x + dx) + ":" + (y + dy) + ":" + (z + dz);
                    ExplosionInfo info = explosionRecords.get(key);
                    if (info != null && !info.isExpired()) {
                        return info;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 批量清理所有过期的记录
     */
    public static void cleanup() {
        records.entrySet().removeIf(entry -> entry.getValue().isExpired());
        explosionRecords.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
}
