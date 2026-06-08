package com.Harry_H2O.pa_module.event;

import com.Harry_H2O.pa_module.KillInfoManager;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.Entity;
import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.config.Debugger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * PVPArena Bukkit 事件监听器 — 伤害重定向核心
 * <p>
 * 本监听器是整个兼容性方案的核心组件，运行在 Bukkit 事件总线上，
 * 处理两类伤害事件：
 * <ol>
 *   <li>{@link EntityDamageByEntityEvent} — TACZ 子弹直接命中的伤害（LOWEST 优先级）</li>
 *   <li>{@link EntityDamageEvent} — TACZ 爆炸范围伤害（BLOCK_EXPLOSION / ENTITY_EXPLOSION）</li>
 * </ol>
 * <p>
 * 核心逻辑：取消原始 TACZ 伤害事件 → 构造正确的 EntityDamageByEntityEvent（damager=Player, cause=ENTITY_ATTACK）
 * 并手动触发 → PVPArena 正常处理假死和击杀归属
 * <p>
 * 重要：不使用 {@code victim.damage()} 重施伤害，因为 Mohist 的伤害桥接会将 DamageCause
 * 错误地继承为 BLOCK_EXPLOSION，导致 PVPArena 的 parseDeathCause() 显示"方块爆炸"。
 * 改为直接构造并触发正确的事件，完全绕过 Mohist 的伤害桥接。
 * <p>
 * 攻击者 UUID 解析策略（多重保障）：
 * <ol>
 *   <li>优先从 KillInfoManager 的直接命中记录读取</li>
 *   <li>其次从 KillInfoManager 的爆炸位置记录读取</li>
 *   <li>最后回退到反射获取 NMS 子弹实体的 owner</li>
 * </ol>
 *
 * @see KillInfoManager 提供受害者→攻击者映射的数据源
 * @see TACZ_listener 在 Forge 端写入映射数据
 */
public class PA_listener implements Listener {

    /**
     * 构造函数，将本实例注册到 Bukkit 事件总线
     */
    public PA_listener() {
        Bukkit.getPluginManager().registerEvents(this, PVPArena.getInstance());
        Debugger.debug("[TaczEventCompatible] PA_listener registered to Bukkit event bus");
    }

    /**
     * 拦截实体对实体的伤害事件（TACZ 子弹直接命中 或 爆炸伤害带实体来源）
     * <p>
     * 处理流程：
     * <ol>
     *   <li>若攻击者已是 Player 且 DamageCause 正确 → 正常事件，跳过</li>
     *   <li>若攻击者已是 Player 但 DamageCause 错误 → Mohist 桥接 bug，修正后重触发</li>
     *   <li>若攻击者非 Player → 解析 TACZ 攻击者，取消原事件，重定向伤害</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            // Mohist 伤害桥接修复：当 victim.damage(damage, attacker) 在爆炸伤害后被调用时，
            // Mohist 可能将 DamageCause 错误地继承为 BLOCK_EXPLOSION 而非 ENTITY_ATTACK，
            // 导致 PVPArena 的 parseDeathCause() 走入 default 分支显示"方块爆炸"。
            fixDamageCauseIfNeeded(event);
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(victim);
        if (arenaPlayer == null || arenaPlayer.getArena() == null) {
            return;
        }

        Arena arena = arenaPlayer.getArena();
        org.bukkit.entity.Entity damager = event.getDamager();

        Debugger.debug(arena, "[TaczEventCompatible] PA_listener: entity damage detected, damager={}, victim={}, damage={}, cause={}",
                damager.getClass().getSimpleName(), victim.getName(), event.getDamage(), event.getCause());

        UUID attackerId = resolveAttackerId(damager, victim, arena);
        if (attackerId == null) {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: could not resolve attacker, skipping");
            return;
        }

        Player attacker = Bukkit.getPlayer(attackerId);
        if (attacker == null) {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: attacker {} is offline, skipping", attackerId);
            return;
        }

        redirectDamage(arena, victim, attacker, event.getDamage(), event);
    }

    /**
     * 拦截爆炸伤害事件（TACZ 榴弹、火箭筒等爆炸范围伤害，无实体来源）
     * <p>
     * 当 TACZ 爆炸性弹药爆炸时，Mohist 可能将伤害桥接为
     * {@link DamageCause#BLOCK_EXPLOSION} 或 {@link DamageCause#ENTITY_EXPLOSION}，
     * 这些事件没有 getDamager() 方法，需要通过 KillInfoManager 的爆炸记录追溯攻击者。
     * <p>
     * 注意：仅处理非 EntityDamageByEntityEvent 的爆炸伤害，
     * EntityDamageByEntityEvent 类型的爆炸伤害由 onEntityDamageByEntity 处理。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        // 仅处理爆炸伤害
        DamageCause cause = event.getCause();
        if (cause != DamageCause.BLOCK_EXPLOSION && cause != DamageCause.ENTITY_EXPLOSION) {
            return;
        }

        // EntityDamageByEntityEvent 由 onEntityDamageByEntity 处理
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(victim);
        if (arenaPlayer == null || arenaPlayer.getArena() == null) {
            return;
        }

        Arena arena = arenaPlayer.getArena();

        Debugger.debug(arena, "[TaczEventCompatible] PA_listener: explosion damage detected (no entity source), victim={}, damage={}, cause={}",
                victim.getName(), event.getDamage(), cause);

        // 策略一：检查是否有该受害者的直接命中记录
        KillInfoManager.KillInfo killInfo = KillInfoManager.getKillInfo(victim.getUniqueId());
        if (killInfo != null) {
            Player attacker = Bukkit.getPlayer(killInfo.getAttackerId());
            if (attacker != null) {
                Debugger.debug(arena, "[TaczEventCompatible] PA_listener: resolved explosion attacker from direct hit record: {}", attacker.getName());
                redirectDamage(arena, victim, attacker, event.getDamage(), event);
                return;
            }
        }

        // 策略二：检查受害者附近是否有 TACZ 爆炸记录
        UUID attackerId = resolveExplosionAttacker(victim, arena);
        if (attackerId != null) {
            Player attacker = Bukkit.getPlayer(attackerId);
            if (attacker != null) {
                Debugger.debug(arena, "[TaczEventCompatible] PA_listener: resolved explosion attacker from nearby explosion record: {}", attacker.getName());
                KillInfoManager.recordDamage(victim.getUniqueId(), attackerId);
                redirectDamage(arena, victim, attacker, event.getDamage(), event);
                return;
            }
        }

        Debugger.debug(arena, "[TaczEventCompatible] PA_listener: could not resolve explosion attacker, skipping");
    }

    /**
     * 解析攻击者 UUID，使用多重保障策略
     * <p>
     * 策略一：从 KillInfoManager 的直接命中记录读取
     * 策略二：从 KillInfoManager 的爆炸位置记录读取
     * 策略三：反射获取 NMS 子弹实体的 owner
     *
     * @param damager  Bukkit 事件中的攻击者实体
     * @param victim   受害者玩家
     * @param arena    竞技场实例（用于 debug 日志）
     * @return 攻击者玩家的 UUID；若无法解析则返回 null
     */
    private UUID resolveAttackerId(org.bukkit.entity.Entity damager, Player victim, Arena arena) {
        UUID victimId = victim.getUniqueId();

        // 策略一：KillInfoManager 直接命中记录
        KillInfoManager.KillInfo killInfo = KillInfoManager.getKillInfo(victimId);
        if (killInfo != null) {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: resolved attacker from KillInfoManager direct hit: {}", killInfo.getAttackerId());
            return killInfo.getAttackerId();
        }

        // 策略二：KillInfoManager 爆炸位置记录（爆炸伤害可能以 EntityDamageByEntityEvent 形式到达）
        UUID explosionAttacker = resolveExplosionAttacker(victim, arena);
        if (explosionAttacker != null) {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: resolved attacker from KillInfoManager explosion record: {}", explosionAttacker);
            return explosionAttacker;
        }

        Debugger.debug(arena, "[TaczEventCompatible] PA_listener: KillInfoManager miss, trying reflection fallback");

        // 策略三：反射获取 NMS 子弹实体的 owner
        try {
            Method getHandle = damager.getClass().getMethod("getHandle");
            Object nmsEntity = getHandle.invoke(damager);
            if (nmsEntity instanceof EntityKineticBullet bullet) {
                Debugger.debug(arena, "[TaczEventCompatible] PA_listener: NMS entity is EntityKineticBullet");
                Entity owner = bullet.getOwner();
                if (owner instanceof net.minecraft.world.entity.player.Player nmsPlayer) {
                    Debugger.debug(arena, "[TaczEventCompatible] PA_listener: resolved attacker from NMS bullet owner: {}",
                            nmsPlayer.getGameProfile().getName());
                    return nmsPlayer.getUUID();
                }
            } else {
                Debugger.debug(arena, "[TaczEventCompatible] PA_listener: NMS entity is NOT EntityKineticBullet: {}",
                        nmsEntity != null ? nmsEntity.getClass().getSimpleName() : "null");
            }
        } catch (NoClassDefFoundError e) {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: NoClassDefFoundError during reflection: {}", e.getMessage());
        } catch (Exception e) {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: Exception during reflection: {}", e.getClass().getSimpleName());
        }

        return null;
    }

    /**
     * 从 KillInfoManager 的爆炸位置记录中查找攻击者
     * <p>
     * 使用 Bukkit 世界名称（与 TACZ_listener 中使用 ServerLevel.getWorld().getName() 一致）
     * 在受害者 ±5 格范围内搜索 TACZ 爆炸记录。
     *
     * @param victim 受害者玩家
     * @param arena  竞技场实例（用于 debug 日志）
     * @return 攻击者 UUID；若未找到则返回 null
     */
    private UUID resolveExplosionAttacker(Player victim, Arena arena) {
        String worldName = victim.getWorld().getName();
        int x = victim.getLocation().getBlockX();
        int y = victim.getLocation().getBlockY();
        int z = victim.getLocation().getBlockZ();

        KillInfoManager.ExplosionInfo explosionInfo = KillInfoManager.getNearbyExplosion(worldName, x, y, z);
        if (explosionInfo != null) {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: found nearby explosion record, attacker={}", explosionInfo.getAttackerId());
            return explosionInfo.getAttackerId();
        }
        return null;
    }

    /**
     * 执行伤害重定向：取消原事件，立即构造正确的 EntityDamageByEntityEvent 并触发
     * <p>
     * 不使用 {@code victim.damage()} 重施伤害，因为 Mohist 的伤害桥接会将 DamageCause
     * 错误地继承为 BLOCK_EXPLOSION，导致 PVPArena 的 parseDeathCause() 显示"方块爆炸"。
     * <p>
     * 改为直接构造 {@link EntityDamageByEntityEvent}（damager=Player, cause=ENTITY_ATTACK）
     * 并通过 {@code callEvent()} 立即触发，完全绕过 Mohist 的伤害桥接。
     * 手动触发的事件不会被 Minecraft 服务器自动应用伤害，因此需要手动扣血。
     * <p>
     * 重要：不使用 {@code runTask()} 延迟触发，原因：
     * <ol>
     *   <li>Mohist 可能在当前 tick 直接通过 Forge 侧应用爆炸伤害，延迟 1 tick 后玩家可能已真实死亡</li>
     *   <li>延迟期间玩家状态可能变化（被其他伤害击杀、离开竞技场等）</li>
     *   <li>立即触发确保 PVPArena 在同一 tick 内处理新事件，与 fixDamageCauseIfNeeded 行为一致</li>
     * </ol>
     * <p>
     * PVPArena 的 {@code handleDeathIfNeeded()} 会在事件处理链中：
     * <ol>
     *   <li>检测到致命伤害时，将生命值设为 2、基础伤害设为 1</li>
     *   <li>调用 {@code handlePlayerDeath()} 处理假死、重生、击杀归属</li>
     * </ol>
     * 因此在 {@code callEvent()} 返回后，只需根据事件的 {@code getFinalDamage()} 手动扣血即可。
     */
    private void redirectDamage(Arena arena, Player victim, Player attacker, double damage, EntityDamageEvent event) {
        Debugger.debug(arena, "[TaczEventCompatible] PA_listener: redirecting damage, cancelling original event (cause={}), {} base damage to {} from {}",
                event.getCause(), damage, victim.getName(), attacker.getName());

        // 取消原始事件（BLOCK_EXPLOSION / ENTITY_EXPLOSION / 子弹实体伤害）
        event.setCancelled(true);

        // 立即构造正确的 EntityDamageByEntityEvent 并触发（不使用 runTask 延迟）
        // 这确保 PVPArena 在同一 tick 内处理新事件，正确执行假死和击杀归属
        EntityDamageByEntityEvent redirectedEvent = new EntityDamageByEntityEvent(
                attacker, victim, DamageCause.ENTITY_ATTACK, damage);
        Bukkit.getPluginManager().callEvent(redirectedEvent);

        // 手动应用伤害：手动触发的事件不会被 Minecraft 服务器自动扣血
        if (!redirectedEvent.isCancelled()) {
            double finalDamage = redirectedEvent.getFinalDamage();
            double newHealth = victim.getHealth() - finalDamage;

            // PVPArena 的 handleDeathIfNeeded() 在致命伤害时会：
            // 1. 将所有 DamageModifier 设为 0
            // 2. 将生命值设为 2
            // 3. 将 BASE damage 设为 1
            // 因此 finalDamage=1, newHealth=2-1=1 > 0，走 setHealth 分支
            if (newHealth > 0) {
                victim.setHealth(newHealth);
            }
            // newHealth <= 0 的情况不应出现（PVPArena 已处理假死），
            // 但作为安全保障，不执行任何操作避免真实死亡

            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: redirected damage applied, finalDamage={}, newHealth={}, victimHealth={}",
                    finalDamage, newHealth, victim.getHealth());
        } else {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: redirected event was cancelled by another handler");
        }
    }

    /**
     * 修正 Mohist 伤害桥接导致的 DamageCause 错误
     * <p>
     * 在 Mohist 混合端中，当 {@code victim.damage(damage, attacker)} 在爆炸伤害事件之后被调用时，
     * Mohist 的事件桥接可能将新事件的 {@link DamageCause} 错误地继承为
     * {@link DamageCause#BLOCK_EXPLOSION} 或 {@link DamageCause#ENTITY_EXPLOSION}，
     * 而非正确的 {@link DamageCause#ENTITY_ATTACK}。
     * <p>
     * 这会导致 PVPArena 的 {@code parseDeathCause()} 方法走入 {@code default} 分支，
     * 将击杀播报显示为"方块爆炸"而非攻击者玩家名。
     * <p>
     * 修复方案：取消 DamageCause 错误的事件，构造正确的 EntityDamageByEntityEvent
     * （damager=Player, cause=ENTITY_ATTACK）并手动触发，然后手动应用伤害。
     *
     * @param event 需要检查的伤害事件
     */
    private void fixDamageCauseIfNeeded(EntityDamageByEntityEvent event) {
        DamageCause cause = event.getCause();
        // 玩家攻击的合法 cause 只有 ENTITY_ATTACK 和 ENTITY_SWEEP_ATTACK
        if (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        // 仅修复竞技场内玩家的伤害原因
        ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(victim);
        if (arenaPlayer == null || arenaPlayer.getArena() == null) {
            return;
        }

        Arena arena = arenaPlayer.getArena();
        Player attacker = (Player) event.getDamager();
        double damage = event.getDamage();

        Debugger.debug(arena, "[TaczEventCompatible] PA_listener: Mohist damage cause mismatch, damager is Player but cause={}, cancelling and refiring with ENTITY_ATTACK", cause);

        // 取消 DamageCause 错误的事件
        event.setCancelled(true);

        // 构造正确的 EntityDamageByEntityEvent（DamageCause = ENTITY_ATTACK）并触发
        EntityDamageByEntityEvent fixedEvent = new EntityDamageByEntityEvent(
                attacker, victim, DamageCause.ENTITY_ATTACK, damage);
        Bukkit.getPluginManager().callEvent(fixedEvent);

        // 手动应用伤害：手动触发的事件不会被 Minecraft 服务器自动应用伤害
        if (!fixedEvent.isCancelled()) {
            double finalDamage = fixedEvent.getFinalDamage();
            double newHealth = victim.getHealth() - finalDamage;
            if (newHealth > 0) {
                victim.setHealth(newHealth);
            }
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: refired event processed, finalDamage={}, newHealth={}", finalDamage, newHealth);
        } else {
            Debugger.debug(arena, "[TaczEventCompatible] PA_listener: refired event was cancelled by another handler");
        }
    }
}
