package cc.lylighte.scorely.event;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * 服务器生命周期事件注册（Fabric API）。
 *
 * <p>职责：把服务器生命周期（启动/停止）与 tick 循环桥接到
 * {@link RefreshScheduler}，驱动定时积分刷新。</p>
 */
public final class ServerEvents {

	private ServerEvents() {
	}

	/**
	 * 注册事件回调。
	 *
	 * @param scheduler 积分刷新调度器
	 */
	public static void register(RefreshScheduler scheduler) {
		// 服务器启动：首刷 + 定时节奏初始化
		ServerLifecycleEvents.SERVER_STARTED.register(scheduler::onServerStarted);
		// 服务器停止：释放引用
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> scheduler.onServerStopped());
		// 每 tick：定时主刷新 + 合并触发消费
		ServerTickEvents.END_SERVER_TICK.register(scheduler::onServerTick);
	}
}
