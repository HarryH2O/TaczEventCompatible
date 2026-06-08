package com.Harry_H2O.pa_module;

import com.Harry_H2O.pa_module.event.PA_listener;
import com.Harry_H2O.pa_module.event.TACZ_listener;
import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.config.Debugger;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PVPArena 模块主类
 * <p>
 * 本模块解决 TACZ（枪械模组）与 PVPArena（竞技场插件）之间的兼容性问题：
 * <ul>
 *   <li>TACZ 子弹的攻击者是子弹实体（Projectile），而非玩家（Player）</li>
 *   <li>PVPArena 的 EntityListener 只处理攻击者为 Player 的伤害事件</li>
 *   <li>因此 TACZ 枪械伤害被 PVPArena 完全忽略，无法正确归属击杀</li>
 * </ul>
 * <p>
 * 核心解决方案：取消+重施伤害（Cancel + Re-apply）
 * <ol>
 *   <li>Forge 端：TACZ_listener 在 EntityHurtByGunEvent.Pre 中记录受害者→攻击者的 UUID 映射</li>
 *   <li>Bukkit 端：PA_listener 以 LOWEST 优先级拦截 TACZ 子弹/爆炸的伤害事件</li>
 *   <li>取消原事件，用 victim.damage(damage, attacker) 重施伤害（此时 damager 为 Player）</li>
 *   <li>PVPArena 正常识别攻击者并处理假死、击杀统计等逻辑</li>
 * </ol>
 * <p>
 * 注意：PVPArena 的 ArenaModule.onThisLoad() 从未被框架调用，
 * 所有初始化必须在构造函数中完成。使用静态 AtomicBoolean 确保只初始化一次。
 *
 * @see TACZ_listener Forge 事件监听器，记录枪击映射和爆炸映射
 * @see PA_listener Bukkit 事件监听器，执行伤害重定向
 * @see KillInfoManager 临时存储受害者→攻击者的映射关系
 */
public class Main extends ArenaModule {

    /**
     * 全局初始化标志，确保监听器只注册一次
     * PVPArena 可能多次实例化模块（如 reload 时），但 Bukkit/Forge 监听器只能注册一次
     */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * 构造函数，注册模块名称并执行首次初始化
     * <p>
     * PVPArena 通过反射调用此无参构造函数来实例化模块。
     * 使用静态 AtomicBoolean 确保监听器注册等操作仅执行一次，
     * 后续竞技场创建的新实例不会重复注册。
     */
    public Main() {
        super("TaczEventCompatible");

        // 后备 Logger，仅用于严重错误输出（不受 debug 模式控制）
        Logger logger = null;
        try {
            PVPArena pvpArena = PVPArena.getInstance();
            if (pvpArena != null) {
                logger = pvpArena.getLogger();
            }
        } catch (Exception ignored) {}

        if (logger == null) {
            logger = Bukkit.getLogger();
        }

        Debugger.debug("[TaczEventCompatible] Main: module instance created");

        if (!initialized.compareAndSet(false, true)) {
            Debugger.debug("[TaczEventCompatible] Main: already initialized globally, skipping init");
            return;
        }

        Debugger.debug("================================================");
        Debugger.debug("[TaczEventCompatible] Module initializing - version " + version());

        // 注册 Forge 端 TACZ 事件监听器
        try {
            new TACZ_listener();
            Debugger.debug("[TaczEventCompatible] TACZ_listener registered to Forge EVENT_BUS");
        } catch (NoClassDefFoundError e) {
            logger.severe("[TaczEventCompatible] TACZ classes not found: " + e.getMessage());
            logger.severe("[TaczEventCompatible] TACZ mod may not be installed. Gun damage mapping will use reflection fallback only.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[TaczEventCompatible] Failed to register TACZ_listener: " + e.getMessage(), e);
        }

        // 注册 Bukkit 端伤害重定向监听器
        try {
            new PA_listener();
            Debugger.debug("[TaczEventCompatible] PA_listener registered to Bukkit event bus");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[TaczEventCompatible] Failed to register PA_listener: " + e.getMessage(), e);
        }

        // 注册定时清理任务
        try {
            Bukkit.getScheduler().runTaskTimer(PVPArena.getInstance(), KillInfoManager::cleanup, 600L, 600L);
            Debugger.debug("[TaczEventCompatible] Cleanup task scheduled (interval: 600 ticks / 30 seconds)");
        } catch (Exception e) {
            logger.log(Level.WARNING, "[TaczEventCompatible] Failed to schedule cleanup task: " + e.getMessage(), e);
        }

        Debugger.debug("[TaczEventCompatible] Module initialized successfully!");
        Debugger.debug("================================================");
    }

    /**
     * 返回模块版本号
     */
    @Override
    public String version() {
        return "2.3.0";
    }

    /**
     * 此钩子在 PVPArena 中从未被调用，保留空实现仅为满足父类抽象要求
     */
    @Override
    public void onThisLoad() {
    }

    /**
     * 竞技场重置钩子，在竞技场结束时由 PVPArena 调用
     */
    @Override
    public void reset(boolean force) {
        Debugger.debug("[TaczEventCompatible] Main: reset called (force={}), running cleanup", force);
        KillInfoManager.cleanup();
    }
}
