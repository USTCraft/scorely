package cc.lylighte.scorely.scoring;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 积分计算引擎（定时全量重算 + 查询）。
 *
 * <p>重算流程：外部（事件层）负责收集玩家统计数据——在线玩家读内存（CompatHelper）、
 * 离线玩家读磁盘（StatsReader），统一为 {@code 玩家 → 统计键值表} 后传入
 * {@link #recalculateAll}。本类为纯 Java 实现，不依赖 Minecraft 类型。</p>
 *
 * <p>全量重算天然幂等（统计累计值 × 权重），无增量状态、无累积误差、故障自愈；
 * 查询永远读 {@link ScoreCache} 快照，零计算开销。</p>
 */
public final class ScoringEngine {

	/** 积分规则（volatile 支持 Phase 8 配置加载后原子替换；读时快照引用）。 */
	private volatile List<ScoringRule> rules;
	private final ScoreCache cache;

	/**
	 * @param rules 积分规则列表（副本持有；配置变更通过 {@link #setRules} 原子替换）
	 */
	public ScoringEngine(List<ScoringRule> rules) {
		this.rules = rules == null ? List.of() : List.copyOf(rules);
		this.cache = new ScoreCache();
	}

	/**
	 * 原子替换积分规则（Phase 8 配置加载时调用）。
	 *
	 * <p>命令树在入口初始化时已捕获本引擎引用，因此热更新采用"改规则而非重建引擎"：
	 * 下次全量重算（定时/手动）自然使用新规则；替换后不立即重算，由调度器按节奏执行。</p>
	 *
	 * @param newRules 新规则列表（null 视为空列表，副本持有）
	 */
	public void setRules(List<ScoringRule> newRules) {
		this.rules = newRules == null ? List.of() : List.copyOf(newRules);
	}

	/**
	 * 全量重算所有玩家的积分并更新缓存快照（幂等）。
	 *
	 * <p>stat 型规则使用统计键值表，advancement 型规则使用已完成进度集合；
	 * 两类数据均无的玩家自动忽略（不出现在榜单）。</p>
	 *
	 * @param statsByPlayer        玩家 → 统计键值表（键格式 {@code "statType/statPath"}，值累计数）
	 * @param advancementsByPlayer 玩家 → 已完成的进度 ID 集合
	 */
	public void recalculateAll(Map<UUID, Map<String, Integer>> statsByPlayer, Map<UUID, Set<String>> advancementsByPlayer) {
		Map<UUID, Map<String, Double>> scores = new HashMap<>();
		if (statsByPlayer == null || statsByPlayer.isEmpty()) {
			if (advancementsByPlayer == null || advancementsByPlayer.isEmpty()) {
				cache.rebuild(scores);
				return;
			}
		}
		Set<UUID> allPlayers = new java.util.HashSet<>();
		if (statsByPlayer != null) {
			allPlayers.addAll(statsByPlayer.keySet());
		}
		if (advancementsByPlayer != null) {
			allPlayers.addAll(advancementsByPlayer.keySet());
		}
		for (UUID player : allPlayers) {
			if (player == null) {
				continue;
			}
			Map<String, Double> ruleScores = computeScores(
					statsByPlayer == null ? Map.of() : statsByPlayer.getOrDefault(player, Map.of()),
					advancementsByPlayer == null ? Set.of() : advancementsByPlayer.getOrDefault(player, Set.of()));
			if (!ruleScores.isEmpty()) {
				scores.put(player, ruleScores);
			}
		}
		cache.rebuild(scores);
	}

	/**
	 * 单玩家重算（Phase 8.1：进服 / {@code /scorely refresh} 路径，不触发全量重算）。
	 *
	 * <p>只计算指定玩家的积分并更新缓存单条；无数据（或全部规则不命中）的玩家从缓存移除，
	 * 语义与全量重算一致。本方法不修改全服快照的其他条目。</p>
	 *
	 * @param player      玩家 UUID
	 * @param stats       玩家统计键值表（可为空表）
	 * @param advancements 玩家已完成的进度 ID 集合（可为空集合）
	 */
	public void recalculatePlayer(UUID player, Map<String, Integer> stats, Set<String> advancements) {
		if (player == null) {
			return;
		}
		Map<String, Double> ruleScores = computeScores(
				stats == null ? Map.of() : stats,
				advancements == null ? Set.of() : advancements);
		cache.updatePlayer(player, ruleScores);
	}

	/**
	 * 按当前规则集计算一个玩家的各规则积分（公共算分逻辑）。
	 *
	 * <p>与 {@code recalculateAll} 内层循环共用：stat 型规则使用统计键值表，
	 * advancement 型规则使用已完成进度集合；不命中或零分的规则不入表
	 * （Phase 10 起保留负分——惩罚规则可产生负积分）。</p>
	 *
	 * @param stats       玩家统计键值表（键格式 {@code "statType/statPath"}，值累计数）
	 * @param advancements 玩家已完成的进度 ID 集合
	 * @return 规则 ID → 积分（空表表示无任何可计分数据）
	 */
	private Map<String, Double> computeScores(Map<String, Integer> stats, Set<String> advancements) {
		Map<String, Double> ruleScores = new HashMap<>();
		for (ScoringRule rule : rules) {
			double score;
			if (rule.isStatType()) {
				score = rule.scoreStat(stats);
			} else if (rule.isAdvancementType()) {
				score = rule.scoreAdvancement(advancements);
			} else {
				continue;
			}
			if (score != 0) {
				ruleScores.put(rule.getId(), score);
			}
		}
		return ruleScores;
	}

	/** 积分缓存（排行榜查询快照）。 */
	public ScoreCache getCache() {
		return cache;
	}

	/**
	 * 获取积分规则列表（命令层展示用，如 {@code admin rule list}）。
	 *
	 * @return 规则列表（不可变副本）
	 */
	public List<ScoringRule> getRules() {
		return rules;
	}

	/**
	 * 获取玩家在某规则下的积分（读缓存快照）。
	 *
	 * @param player 玩家 UUID
	 * @param ruleId 规则 ID
	 * @return 积分（玩家或规则不存在时返回 0）
	 */
	public double getPlayerScore(UUID player, String ruleId) {
		return cache.getPlayerScore(player, ruleId);
	}

	/**
	 * 获取玩家总分（读缓存快照）。
	 *
	 * @param player 玩家 UUID
	 * @return 总分（玩家不存在时返回 0）
	 */
	public double getPlayerTotalScore(UUID player) {
		return cache.getPlayerTotalScore(player);
	}

	/**
	 * 获取某规则下的排行榜（读缓存快照）。
	 *
	 * <p>排序方向随规则配置（Phase 10 起）：{@code sort=asc} 升序（惩罚榜扣最多在前），
	 * 缺省降序（正榜得分最多在前）。</p>
	 *
	 * @param ruleId 规则 ID
	 * @param limit  返回条数上限（{@code <= 0} 表示不限制）
	 * @return 排行榜条目，按规则排序方向排列
	 */
	public List<ScoreEntry> getLeaderboard(String ruleId, int limit) {
		return cache.getLeaderboard(ruleId, limit, isAscending(ruleId));
	}

	/** 规则是否配置为升序（找不到规则时按降序处理）。 */
	private boolean isAscending(String ruleId) {
		for (ScoringRule rule : rules) {
			if (rule != null && ruleId != null && ruleId.equals(rule.getId())) {
				return ScoringRule.SORT_ASC.equals(rule.getSort());
			}
		}
		return false;
	}

	/**
	 * 获取总榜（读缓存快照）。
	 *
	 * @param limit 返回条数上限（{@code <= 0} 表示不限制）
	 * @return 排行榜条目，按总分降序
	 */
	public List<ScoreEntry> getTotalLeaderboard(int limit) {
		return cache.getTotalLeaderboard(limit);
	}
}
