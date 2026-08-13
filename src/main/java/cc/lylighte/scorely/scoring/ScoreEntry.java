package cc.lylighte.scorely.scoring;

import java.util.UUID;

/**
 * 排行榜条目（玩家 + 积分）。
 *
 * <p>由 {@link ScoreCache} 的排行榜查询返回，按积分降序排列。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 *
 * @param player 玩家 UUID
 * @param score  积分（某规则下或总分）
 */
public record ScoreEntry(UUID player, double score) {
}
