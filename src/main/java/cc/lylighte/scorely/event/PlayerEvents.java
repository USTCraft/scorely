package cc.lylighte.scorely.event;

import cc.lylighte.scorely.config.ConfigManager;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * 玩家事件注册（Fabric API）。
 *
 * <p>职责：玩家登录完成后：</p>
 * <ul>
 *   <li>触发单玩家积分刷新（Phase 8.1：只算该玩家，不消耗全服配额）；</li>
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
			scheduler.refreshPlayer(handler.getPlayer().getUUID());
			configManager.updatePlayerName(
					handler.getPlayer().getUUID(),
					handler.getPlayer().getName().getString());
		});
	}
}
