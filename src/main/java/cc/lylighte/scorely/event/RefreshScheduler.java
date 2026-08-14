package cc.lylighte.scorely.event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cc.lylighte.scorely.Scorely;
import cc.lylighte.scorely.compat.CompatHelper;
import cc.lylighte.scorely.scoring.DefaultRules;
import cc.lylighte.scorely.scoring.ScoringEngine;
import cc.lylighte.scorely.util.Result;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 积分刷新调度器（定时主循环 + 合并触发 + 数据收集）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><strong>定时主循环</strong>：每 {@code refreshIntervalMinutes} 分钟必刷一次（默认 5，
 *       可配置），保证数据最终一致；</li>
 *   <li><strong>合并触发</strong>：低价值触发只置 {@code pending} 标记，由 tick 循环统一消费——
 *       多人同时进服只刷一次（Phase 8.3 起无配额限制，仅作批处理合并）；</li>
 *   <li><strong>全量重算入口约束</strong>（Phase 8.3）：非定时全量重算仅由管理命令触发
 *       （{@code admin refresh} / {@code admin reload}），普通玩家只能走单玩家路径
 *       {@link #refreshPlayer}，不触发全服重算；</li>
 *   <li><strong>磁盘优化</strong>：在线玩家走内存读取（CompatHelper），离线玩家走
 *       {@link PlayerDataCache} 指纹缓存（mtime + size 未变化不重读文件），
 *       低频（每 {@link #RECONCILE_PERIODS} 个周期）全扫目录补充新玩家。</li>
 * </ul>
 *
 * <p><strong>线程约束</strong>：所有方法仅在服务器线程（tick 线程 / 命令执行线程）调用。</p>
 */
public final class RefreshScheduler {

	/** 每秒 tick 数。 */
	private static final long TICKS_PER_SECOND = 20L;
	/** 每 N 个周期全扫一次 stats 目录，补充新增离线玩家。 */
	private static final int RECONCILE_PERIODS = 10;
	/** 离线玩家统计文件名后缀。 */
	private static final String JSON_SUFFIX = ".json";

	private final ScoringEngine engine;
	private final PlayerDataCache dataCache = new PlayerDataCache();

	/** 刷新周期（分钟），默认 5，由 config.json 的 refreshIntervalMinutes 覆盖。 */
	private int refreshIntervalMinutes = DefaultRules.REFRESH_INTERVAL_MINUTES;

	private MinecraftServer server;
	private long tickCounter;
	/** 下一次定时主刷新所在的 tick。 */
	private long nextScheduledTick;
	/** 合并触发标记（玩家加入等置位）。 */
	private boolean pendingRefresh;
	/** 已执行的周期数（用于低频目录 reconcile）。 */
	private int periodCount;

	public RefreshScheduler(ScoringEngine engine) {
		this.engine = engine;
	}

	/** 设置刷新周期（分钟）。配置加载时调用。 */
	public void setRefreshIntervalMinutes(int minutes) {
		this.refreshIntervalMinutes = Math.max(1, minutes);
	}

	/** 服务器启动：构建进度帧映射 → 全扫目录登记已知玩家 → 立即首刷 → 设定定时节奏。 */
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		this.tickCounter = 0;
		this.periodCount = 0;
		this.pendingRefresh = false;
		// Phase 12：构建进度帧映射（服务端实时注册表，含 mod 进度）并输出分层统计日志（运行时校准依据）
		Map<String, String> frames = CompatHelper.readAdvancementFrames(server);
		engine.setAdvancementFrames(frames);
		logFrameCounts(frames);
		reconcileKnownPlayers();
		collectAndRecalculate();
		this.nextScheduledTick = intervalTicks();
		Scorely.LOGGER.info("Scorely initial recalculation done, next scheduled refresh in {} min", refreshIntervalMinutes);
	}

	/** 输出进度帧分层统计日志（task/goal/challenge 数量，SCORING_PLAN 2.2 校准）。 */
	private void logFrameCounts(Map<String, String> frames) {
		if (frames.isEmpty()) {
			return;
		}
		Map<String, Integer> counts = new HashMap<>();
		for (String frame : frames.values()) {
			counts.merge(frame, 1, Integer::sum);
		}
		StringBuilder sb = new StringBuilder();
		counts.keySet().stream().sorted().forEach(k -> sb.append(k).append('=').append(counts.get(k)).append(", "));
		if (!sb.isEmpty()) {
			sb.setLength(sb.length() - 2);
		}
		Scorely.LOGGER.info("Scorely advancement frames: total {}, {}", frames.size(), sb);
	}


	/**
	 * 服务器 tick（{@code EndTick.onEndTick(MinecraftServer)} 回调）：
	 * 定时主刷新 + 合并触发消费。
	 */
	public void onServerTick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		tickCounter++;

		// 定时主刷新（到点必刷，周期轮转）
		if (tickCounter >= nextScheduledTick) {
			pendingRefresh = false;
			periodCount++;
			collectAndRecalculate();
			nextScheduledTick = tickCounter + intervalTicks();
			if (periodCount % RECONCILE_PERIODS == 0) {
				reconcileKnownPlayers();
			}
			return;
		}

		// 合并触发消费
		if (pendingRefresh) {
			pendingRefresh = false;
			collectAndRecalculate();
		}
	}

	/**
	 * 请求一次额外刷新（低价值批量触发的预留入口）。
	 *
	 * <p>Phase 8.1 起玩家进服已改走 {@link #refreshPlayer} 单玩家路径，本方法暂无调用方；
	 * 保留 pending 合并机制作为未来低价值触发源（如批量事件）的公共入口：
	 * 只置标记，由 tick 循环统一消费（批处理合并，无配额限制）。</p>
	 */
	public void requestRefresh() {
		pendingRefresh = true;
	}

	/**
	 * 单玩家刷新（Phase 8.1：进服 / {@code /scorely refresh} 路径）。
	 *
	 * <p>只重算指定玩家的积分并更新缓存单条——<strong>不触发全服重算</strong>、
	 * 不置 pending 标记。在线玩家走内存读取，离线玩家走指纹缓存读取（与全量路径一致）。</p>
	 *
	 * @param uuid 玩家 UUID
	 */
	public void refreshPlayer(UUID uuid) {
		MinecraftServer s = this.server;
		if (s == null || uuid == null) {
			return;
		}

		ServerPlayer online = s.getPlayerList().getPlayer(uuid);
		if (online != null) {
			// 在线：内存读取（零磁盘）
			dataCache.addKnownPlayer(uuid);
			engine.recalculatePlayer(uuid,
					CompatHelper.readPlayerStats(online),
					CompatHelper.readCompletedAdvancements(online));
			return;
		}

		// 离线：指纹缓存读取（文件未变化不重读）
		try {
			Path statsDir = s.getWorldPath(LevelResource.PLAYER_STATS_DIR);
			Path advancementsDir = s.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
			dataCache.addKnownPlayer(uuid);
			dataCache.refresh(uuid, statsDir, advancementsDir);
			engine.recalculatePlayer(uuid, dataCache.getStats(uuid), dataCache.getAdvancements(uuid));
		} catch (IOException e) {
			Scorely.LOGGER.warn("Scorely failed to read player data uuid={}: {}", uuid, e.toString());
		}
	}

	/**
	 * 手动刷新（{@code /scorely admin refresh} 调用，Phase 8.3 起无配额限制）。
	 *
	 * <p>直接执行全量重算；全量重算入口仅限管理命令（OP 门禁），普通玩家无法触发。</p>
	 *
	 * @return 执行结果（失败时附原因，如服务器未就绪）
	 */
	public Result refreshNow() {
		if (server == null) {
			return Result.failure("scheduler.server_not_ready");
		}
		pendingRefresh = false;
		collectAndRecalculate();
		return Result.success("scheduler.refreshed");
	}

	/** 服务器停止：释放服务器引用。 */
	public void onServerStopped() {
		this.server = null;
	}

	/** 刷新周期换算为 tick 数。 */
	private long intervalTicks() {
		return (long) refreshIntervalMinutes * 60 * TICKS_PER_SECOND;
	}

	/** 收集全部玩家数据并全量重算（在线读内存，离线读指纹缓存）。 */
	private void collectAndRecalculate() {
		MinecraftServer s = this.server;
		if (s == null) {
			return;
		}

		Map<UUID, Map<String, Integer>> statsByPlayer = new HashMap<>();
		Map<UUID, Set<String>> advancementsByPlayer = new HashMap<>();
		Set<UUID> online = new HashSet<>();

		// 在线玩家：内存读取（零磁盘）
		for (ServerPlayer player : s.getPlayerList().getPlayers()) {
			UUID uuid = player.getUUID();
			online.add(uuid);
			dataCache.addKnownPlayer(uuid);
			statsByPlayer.put(uuid, CompatHelper.readPlayerStats(player));
			advancementsByPlayer.put(uuid, CompatHelper.readCompletedAdvancements(player));
		}

		// 离线玩家：指纹缓存读取（文件未变化不重读）
		Path statsDir = s.getWorldPath(LevelResource.PLAYER_STATS_DIR);
		Path advancementsDir = s.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
		for (UUID uuid : dataCache.getKnownPlayers()) {
			if (online.contains(uuid)) {
				continue;
			}
			try {
				dataCache.refresh(uuid, statsDir, advancementsDir);
				Map<String, Integer> stats = dataCache.getStats(uuid);
				Set<String> advancements = dataCache.getAdvancements(uuid);
				if (!stats.isEmpty()) {
					statsByPlayer.put(uuid, stats);
				}
				if (!advancements.isEmpty()) {
					advancementsByPlayer.put(uuid, advancements);
				}
			} catch (IOException e) {
				Scorely.LOGGER.warn("Scorely failed to read offline player data uuid={}: {}", uuid, e.toString());
			}
		}

		engine.recalculateAll(statsByPlayer, advancementsByPlayer);
		Scorely.LOGGER.info("Scorely recalculation done: {} online, {} offline players",
				online.size(), dataCache.getKnownPlayers().size() - online.size());
	}

	/** 全扫 stats 目录，把新出现的玩家登记进已知玩家集合（低频）。 */
	private void reconcileKnownPlayers() {
		MinecraftServer s = this.server;
		if (s == null) {
			return;
		}
		Path statsDir = s.getWorldPath(LevelResource.PLAYER_STATS_DIR);
		try (var stream = Files.list(statsDir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(JSON_SUFFIX))
					.forEach(p -> {
						String name = p.getFileName().toString();
						try {
							dataCache.addKnownPlayer(UUID.fromString(name.substring(0, name.length() - JSON_SUFFIX.length())));
						} catch (IllegalArgumentException ignored) {
							// 非 UUID 命名的文件（如旧版本残留），跳过
						}
					});
		} catch (IOException e) {
			Scorely.LOGGER.warn("Scorely failed to scan player stats directory: {}", e.toString());
		}
	}
}
