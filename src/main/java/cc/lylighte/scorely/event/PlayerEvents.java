package cc.lylighte.scorely.event;

import cc.lylighte.scorely.config.ConfigManager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * 玩家事件注册（Fabric API）。
 *
 * <p>职责：玩家登录完成后：</p>
 * <ul>
 *   <li>请求一次积分刷新——只置 {@code pending} 标记，由 {@link RefreshScheduler}
 *       在配额内统一执行（合并多人同时进服、防刷）；</li>
 *   <li>记录玩家显示名到 {@link ConfigManager} 名称缓存（离线展示用，脏标记落盘）。</li>
 * </ul>
 */
public final class PlayerEvents {

	private PlayerEvents() {
	}

	/**
	 * 注册事件回调。
	 *
	 * @param scheduler    积分刷新调度器
	 * @param configManager 配置管理（记录玩家名称）
	 */
	public static void register(RefreshScheduler scheduler, ConfigManager configManager) {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			scheduler.requestRefresh();
			configManager.updatePlayerName(
					handler.getPlayer().getUUID(),
					handler.getPlayer().getName().getString());
		});
	}
}
