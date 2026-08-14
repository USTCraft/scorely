package cc.lylighte.scorely.event;

import cc.lylighte.scorely.config.ConfigManager;
import cc.lylighte.scorely.scoring.ScoringEngine;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * 服务器生命周期事件注册（Fabric API）。
 *
 * <p>职责：把服务器生命周期（启动/停止）与 tick 循环桥接到
 * {@link RefreshScheduler} 与 {@link ConfigManager}：</p>
 * <ul>
 *   <li>启动：先加载配置（rules + refreshInterval）→ 替换引擎规则 → 设置调度器间隔
 *       → 调度器首刷（保证配置先于首次重算生效）；</li>
 *   <li>停止：名称缓存脏标记落盘；</li>
 *   <li>每 tick：定时主刷新 + 配置兜底落盘检查。</li>
 * </ul>
 */
public final class ServerEvents {

	private ServerEvents() {
	}

	/**
	 * 注册事件回调。
	 *
	 * @param scheduler    积分刷新调度器
	 * @param configManager 配置管理（加载顺序先于调度器首刷）
	 * @param engine       积分引擎（配置加载后原子替换规则）
	 */
	public static void register(RefreshScheduler scheduler, ConfigManager configManager, ScoringEngine engine) {
		// 服务器启动：配置加载 → 规则替换 → 间隔生效 → 首刷
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			configManager.load();
			engine.setRules(configManager.getRules());
			scheduler.setRefreshIntervalMinutes(configManager.getRefreshIntervalMinutes());
			scheduler.onServerStarted(server);
		});
		// 服务器停止：名称缓存落盘 + 释放引用
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			configManager.savePlayersIfDirty();
			scheduler.onServerStopped();
		});
		// 每 tick：定时主刷新 + 配置兜底落盘检查
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			scheduler.onServerTick(server);
			configManager.onServerTick();
		});
	}
}
