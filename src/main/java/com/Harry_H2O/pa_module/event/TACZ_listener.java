package com.Harry_H2O.pa_module.event;

import com.Harry_H2O.pa_module.KillInfoManager;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.slipcor.pvparena.config.Debugger;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * TACZ（枪械模组）Forge 事件监听器
 * <p>
 * 本监听器运行在 Forge/Minecraft 服务端的事件总线上，
 * 监听两类事件：
 * <ol>
 *   <li>{@link EntityHurtByGunEvent.Pre} — 子弹直接命中实体时记录攻击者和武器信息</li>
 *   <li>{@link ExplosionEvent.Start} — TACZ 爆炸性弹药（榴弹、火箭筒）爆炸时记录攻击者</li>
 * </ol>
 * <p>
 * 数据流：
 * <ul>
 *   <li>子弹命中 → 此监听器记录 → KillInfoManager.recordDamage() → PA_listener 读取</li>
 *   <li>弹药爆炸 → 此监听器记录 → KillInfoManager.recordExplosion() → PA_listener 读取</li>
 * </ul>
 *
 * @see KillInfoManager 存储击杀信息和爆炸信息的映射管理器
 * @see PA_listener 在 Bukkit 端消费此映射并执行伤害重定向
 */
public class TACZ_listener {

    /**
     * 构造函数，将本实例注册到 Forge 事件总线
     * 注册后 @SubscribeEvent 标注的方法会自动被 Forge 调度
     */
    public TACZ_listener() {
        MinecraftForge.EVENT_BUS.register(this);
        Debugger.debug("[TaczEventCompatible] TACZ_listener registered to Forge EVENT_BUS");
    }

    /**
     * 监听 TACZ 枪击伤害前置事件
     * <p>
     * 当 TACZ 子弹命中任意实体且即将造成伤害时触发。
     * 本方法仅关注以下情况：
     * <ol>
     *   <li>事件发生在服务端（非客户端）</li>
     *   <li>受害者和攻击者都是玩家（Player）</li>
     * </ol>
     * 满足条件后将攻击者 UUID 写入 KillInfoManager。
     *
     * @param event TACZ 枪击伤害前置事件
     */
    @SubscribeEvent
    public void onEntityHurtByGunPre(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }

        net.minecraft.world.entity.Entity hurtEntity = event.getHurtEntity();
        LivingEntity attacker = event.getAttacker();

        if (hurtEntity == null || attacker == null) {
            Debugger.debug("[TaczEventCompatible] TACZ event: hurtEntity or attacker is null, skipping");
            return;
        }

        if (!(hurtEntity instanceof Player) || !(attacker instanceof Player)) {
            Debugger.debug("[TaczEventCompatible] TACZ event: non-player entity involved (hurt={}, attacker={}), skipping",
                    hurtEntity.getClass().getSimpleName(), attacker.getClass().getSimpleName());
            return;
        }

        UUID victimId = hurtEntity.getUUID();
        UUID attackerId = attacker.getUUID();

        Debugger.debug("[TaczEventCompatible] TACZ gun hit: victim={}, attacker={}, recording damage mapping",
                ((Player) hurtEntity).getGameProfile().getName(),
                ((Player) attacker).getGameProfile().getName());

        KillInfoManager.recordDamage(victimId, attackerId);
    }

    /**
     * 监听 Forge 爆炸事件
     * <p>
     * 当 TACZ 爆炸性弹药（榴弹、火箭筒等）爆炸时触发。
     * 检查爆炸源是否为 TACZ 的 {@link EntityKineticBullet}，
     * 如果是则记录爆炸位置和攻击者信息到 KillInfoManager，
     * 以便 PA_listener 在处理爆炸伤害时能追溯到攻击者。
     * <p>
     * 时序保证：{@link ExplosionEvent.Start} 在 {@link Explosion#explode()} 之前触发，
     * 而 {@code explode()} 内部才会调用 {@code entity.hurt()} 产生 Bukkit 伤害事件，
     * 因此映射数据保证在 Bukkit 事件到达之前就已写入。
     *
     * @param event Forge 爆炸开始事件
     */
    @SubscribeEvent
    public void onExplosionStart(ExplosionEvent.Start event) {
        Explosion explosion = event.getExplosion();
        net.minecraft.world.entity.Entity exploder = explosion.getDirectSourceEntity();

        if (exploder == null) {
            return;
        }

        // 检查爆炸源是否为 TACZ 子弹实体
        if (!(exploder instanceof EntityKineticBullet bullet)) {
            return;
        }

        // 获取子弹的拥有者（开枪玩家）
        net.minecraft.world.entity.Entity owner = bullet.getOwner();
        if (!(owner instanceof Player attackerPlayer)) {
            Debugger.debug("[TaczEventCompatible] TACZ explosion: owner is not a player ({})",
                    owner != null ? owner.getClass().getSimpleName() : "null");
            return;
        }

        // 记录爆炸位置和攻击者信息
        // 使用 Bukkit 世界名称（与 PA_listener 中 victim.getWorld().getName() 一致）
        // ServerLevel.getWorld() 是 Mohist 运行时添加的方法，编译时不存在，需通过反射调用
        String worldName;
        try {
            Method getWorldMethod = event.getLevel().getClass().getMethod("getWorld");
            Object bukkitWorld = getWorldMethod.invoke(event.getLevel());
            if (bukkitWorld instanceof org.bukkit.World bw) {
                worldName = bw.getName();
            } else {
                worldName = event.getLevel().dimension().location().toString();
            }
        } catch (NoSuchMethodException e) {
            // 非 Mohist 环境，回退到维度位置字符串
            worldName = event.getLevel().dimension().location().toString();
        } catch (Exception e) {
            worldName = event.getLevel().dimension().location().toString();
        }
        int x = (int) explosion.getPosition().x;
        int y = (int) explosion.getPosition().y;
        int z = (int) explosion.getPosition().z;
        String locationKey = worldName + ":" + x + ":" + y + ":" + z;

        UUID attackerId = attackerPlayer.getUUID();

        Debugger.debug("[TaczEventCompatible] TACZ explosion: owner={}, location={}:{}, recording explosion mapping",
                attackerPlayer.getGameProfile().getName(), worldName, x + ":" + y + ":" + z);

        KillInfoManager.recordExplosion(locationKey, attackerId);
    }
}
