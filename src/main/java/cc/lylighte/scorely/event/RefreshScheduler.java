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
 * 积分刷新调度器（定时主循环 + 合并触发 + 周期配额 + 数据收集）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><strong>定时主循环</strong>：每 {@code refreshIntervalMinutes} 分钟必刷一次（默认 5，
 *       可配置），保证数据最终一致；</li>
 *   <li><strong>合并触发</strong>：玩家加入等低价值触发只置 {@code pending} 标记，
 *       由 tick 循环统一消费——多人同时进服只刷一次；</li>
 *   <li><strong>周期配额</strong>：每个刷新周期内额外刷新（玩家加入 + 手动 refresh）最多
 *       {@link #MAX_EXTRA_REFRESHES} 次，防止反复进出/连点命令导致频繁全量重算；
 *       定时主刷新执行时配额清零（周期轮转）；</li>
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
	/** 每周期额外刷新次数上限（Phase 8 由 config.json 配置化）。 */
	private static final int MAX_EXTRA_REFRESHES = 3;
	/** 每 N 个周期全扫一次 stats 目录，补充新增离线玩家。 */
	private static final int RECONCILE_PERIODS = 10;
	/** 离线玩家统计文件名后缀。 */
	private static final String JSON_SUFFIX = ".json";

	private final ScoringEngine engine;
	private final PlayerDataCache dataCache = new PlayerDataCache();

	/** 刷新周期（分钟），默认 5，Phase 8 由 config.json 覆盖。 */
	private int refreshIntervalMinutes = DefaultRules.REFRESH_INTERVAL_MINUTES;

	private MinecraftServer server;
	private long tickCounter;
	/** 下一次定时主刷新所在的 tick。 */
	private long nextScheduledTick;
	/** 合并触发标记（玩家加入等置位）。 */
	private boolean pendingRefresh;
	/** 本周期已使用的额外刷新次数。 */
	private int extraRefreshesUsed;
	/** 已执行的周期数（用于低频目录 reconcile）。 */
	private int periodCount;

	public RefreshScheduler(ScoringEngine engine) {
		this.engine = engine;
	}

	/** 设置刷新周期（分钟）。Phase 8 配置加载时调用。 */
	public void setRefreshIntervalMinutes(int minutes) {
		this.refreshIntervalMinutes = Math.max(1, minutes);
	}

	/** 服务器启动：全扫目录登记已知玩家 → 立即首刷 → 设定定时节奏。 */
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		this.tickCounter = 0;
		this.extraRefreshesUsed = 0;
		this.periodCount = 0;
		this.pendingRefresh = false;
		reconcileKnownPlayers();
		collectAndRecalculate();
		this.nextScheduledTick = intervalTicks();
		Scorely.LOGGER.info("Scorely 积分首次重算完成，下次定时刷新 {} 分钟后", refreshIntervalMinutes);
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

		// 定时主刷新（到点必刷，配额清零，周期轮转）
		if (tickCounter >= nextScheduledTick) {
			pendingRefresh = false;
			extraRefreshesUsed = 0;
			periodCount++;
			collectAndRecalculate();
			nextScheduledTick = tickCounter + intervalTicks();
			if (periodCount % RECONCILE_PERIODS == 0) {
				reconcileKnownPlayers();
			}
			return;
		}

		// 合并触发（配额内消费）
		if (pendingRefresh && extraRefreshesUsed < MAX_EXTRA_REFRESHES) {
			pendingRefresh = false;
			extraRefreshesUsed++;
			collectAndRecalculate();
		}
	}

	/** 请求一次额外刷新（玩家加入等触发；实际执行受配额限制）。 */
	public void requestRefresh() {
		pendingRefresh = true;
	}

	/**
	 * 手动刷新（{@code /scorely admin refresh} 调用）。
	 *
	 * <p>直接消费配额执行；配额用尽时拒绝并给出提示。</p>
	 *
	 * @return 执行结果（失败时附原因）
	 */
	public Result refreshNow() {
		if (server == null) {
			return Result.failure("服务器未就绪，无法刷新");
		}
		if (extraRefreshesUsed >= MAX_EXTRA_REFRESHES) {
			return Result.failure("本轮额外刷新配额已用完（" + MAX_EXTRA_REFRESHES + "/"
					+ MAX_EXTRA_REFRESHES + "），等待下轮定时刷新");
		}
		pendingRefresh = false;
		extraRefreshesUsed++;
		collectAndRecalculate();
		return Result.success("积分已刷新（本轮额外 " + extraRefreshesUsed + "/"
				+ MAX_EXTRA_REFRESHES + "）");
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
				Scorely.LOGGER.warn("读取离线玩家数据失败 uuid={}: {}", uuid, e.toString());
			}
		}

		engine.recalculateAll(statsByPlayer, advancementsByPlayer);
		Scorely.LOGGER.info("Scorely 积分重算完成：在线 {} 人，离线 {} 人",
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
			Scorely.LOGGER.warn("扫描玩家统计目录失败: {}", e.toString());
		}
	}
}
