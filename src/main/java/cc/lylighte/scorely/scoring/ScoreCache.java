package cc.lylighte.scorely.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 积分缓存（排行榜查询快照）。
 *
 * <p>存储 {@code 玩家 → 规则ID → 分数} 的不可变快照，由 {@link ScoringEngine#recalculateAll} 原子替换。
 * 查询线程永远读到完整一致的快照，无需加锁。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class ScoreCache {

	/** 玩家 → 规则ID → 分数（volatile 保证写线程原子替换对读线程可见）。 */
	private volatile Map<UUID, Map<String, Double>> playerScores = Map.of();

	/**
	 * 全量重建缓存（原子替换整个快照）。
	 *
	 * @param scores 玩家 → 规则ID → 分数（内部会做不可变拷贝，调用方可安全复用传入的 Map）
	 */
	public void rebuild(Map<UUID, Map<String, Double>> scores) {
		if (scores == null || scores.isEmpty()) {
			playerScores = Map.of();
			return;
		}
		Map<UUID, Map<String, Double>> copy = new HashMap<>(scores.size());
		for (Map.Entry<UUID, Map<String, Double>> entry : scores.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
				continue;
			}
			copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
		}
		playerScores = Map.copyOf(copy);
	}

	/**
	 * 单玩家积分更新（Phase 8.1：单玩家刷新路径，原子替换整个快照副本）。
	 *
	 * <p>语义与全量重算一致：空分数表 = 从缓存移除该玩家（无统计数据的玩家不出现在榜单）。
	 * 服务器线程约束下单条写入频率低（进服 / 手动 refresh），copy-on-write 开销可忽略。</p>
	 *
	 * @param player     玩家 UUID
	 * @param ruleScores 该玩家的规则 → 分数表（内部做不可变拷贝；空表则移除该玩家）
	 */
	public void updatePlayer(UUID player, Map<String, Double> ruleScores) {
		if (player == null) {
			return;
		}
		Map<UUID, Map<String, Double>> copy = new HashMap<>(playerScores);
		if (ruleScores == null || ruleScores.isEmpty()) {
			copy.remove(player);
		} else {
			copy.put(player, Map.copyOf(ruleScores));
		}
		playerScores = Map.copyOf(copy);
	}

	/** 全部已计入积分的玩家 UUID。 */
	public Set<UUID> getKnownPlayers() {
		return playerScores.keySet();
	}

	/**
	 * 获取玩家在某规则下的积分。
	 *
	 * @param player 玩家 UUID
	 * @param ruleId 规则 ID
	 * @return 积分（玩家或规则不存在时返回 0）
	 */
	public double getPlayerScore(UUID player, String ruleId) {
		Map<String, Double> ruleScores = playerScores.get(player);
		if (ruleScores == null) {
			return 0;
		}
		Double score = ruleScores.get(ruleId);
		return score == null ? 0 : score;
	}

	/**
	 * 获取玩家总分（所有规则之和）。
	 *
	 * @param player 玩家 UUID
	 * @return 总分（玩家不存在时返回 0）
	 */
	public double getPlayerTotalScore(UUID player) {
		Map<String, Double> ruleScores = playerScores.get(player);
		if (ruleScores == null) {
			return 0;
		}
		double total = 0;
		for (double score : ruleScores.values()) {
			total += score;
		}
		return total;
	}

	/**
	 * 获取某规则下的排行榜（按积分降序；同分按 UUID 升序保证确定性）。
	 *
	 * @param ruleId 规则 ID
	 * @param limit  返回条数上限（{@code <= 0} 表示不限制）
	 * @return 排行榜条目
	 */
	public List<ScoreEntry> getLeaderboard(String ruleId, int limit) {
		List<ScoreEntry> entries = new ArrayList<>();
		for (Map.Entry<UUID, Map<String, Double>> entry : playerScores.entrySet()) {
			Double score = entry.getValue().get(ruleId);
			if (score != null && score > 0) {
				entries.add(new ScoreEntry(entry.getKey(), score));
			}
		}
		entries.sort(Comparator.comparingDouble(ScoreEntry::score).reversed()
			.thenComparing(ScoreEntry::player));
		return limit > 0 && entries.size() > limit ? entries.subList(0, limit) : entries;
	}

	/**
	 * 获取总榜（按总分降序；同分按 UUID 升序保证确定性）。
	 *
	 * @param limit 返回条数上限（{@code <= 0} 表示不限制）
	 * @return 排行榜条目
	 */
	public List<ScoreEntry> getTotalLeaderboard(int limit) {
		List<ScoreEntry> entries = new ArrayList<>();
		for (Map.Entry<UUID, Map<String, Double>> entry : playerScores.entrySet()) {
			double total = 0;
			for (double score : entry.getValue().values()) {
				total += score;
			}
			if (total > 0) {
				entries.add(new ScoreEntry(entry.getKey(), total));
			}
		}
		entries.sort(Comparator.comparingDouble(ScoreEntry::score).reversed()
			.thenComparing(ScoreEntry::player));
		return limit > 0 && entries.size() > limit ? entries.subList(0, limit) : entries;
	}
}
