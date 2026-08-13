package cc.lylighte.scorely.event;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * 玩家事件注册（Fabric API）。
 *
 * <p>职责：玩家登录完成后请求一次积分刷新——只置 {@code pending} 标记，
 * 由 {@link RefreshScheduler} 在配额内统一执行（合并多人同时进服、防刷）。</p>
 */
public final class PlayerEvents {

	private PlayerEvents() {
	}

	/**
	 * 注册事件回调。
	 *
	 * @param scheduler 积分刷新调度器
	 */
	public static void register(RefreshScheduler scheduler) {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> scheduler.requestRefresh());
	}
}
