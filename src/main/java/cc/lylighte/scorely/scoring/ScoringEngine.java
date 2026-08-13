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

	private final List<ScoringRule> rules;
	private final ScoreCache cache;

	/**
	 * @param rules 积分规则列表（副本持有；规则变更时需重新构造引擎或由配置层重建）
	 */
	public ScoringEngine(List<ScoringRule> rules) {
		this.rules = rules == null ? List.of() : List.copyOf(rules);
		this.cache = new ScoreCache();
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
			Map<String, Double> ruleScores = new HashMap<>();
			for (ScoringRule rule : rules) {
				double score;
				if (rule.isStatType()) {
					score = rule.scoreStat(statsByPlayer == null ? Map.of() : statsByPlayer.getOrDefault(player, Map.of()));
				} else if (rule.isAdvancementType()) {
					score = rule.scoreAdvancement(advancementsByPlayer == null ? Set.of() : advancementsByPlayer.getOrDefault(player, Set.of()));
				} else {
					continue;
				}
				if (score > 0) {
					ruleScores.put(rule.getId(), score);
				}
			}
			if (!ruleScores.isEmpty()) {
				scores.put(player, ruleScores);
			}
		}
		cache.rebuild(scores);
	}

	/** 积分缓存（排行榜查询快照）。 */
	public ScoreCache getCache() {
		return cache;
	}

	/**
	 * 获取积分规则列表（命令层展示用，如 {@code admin rule list}）。
	 *
	 * @return 规则列表（不可变副本，构造时已 {@link List#copyOf}）
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
	 * @param ruleId 规则 ID
	 * @param limit  返回条数上限（{@code <= 0} 表示不限制）
	 * @return 排行榜条目，按积分降序
	 */
	public List<ScoreEntry> getLeaderboard(String ruleId, int limit) {
		return cache.getLeaderboard(ruleId, limit);
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
