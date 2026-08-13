package cc.lylighte.scorely.stats;

import java.util.Set;

/**
 * 统计类型（StatType）键定义。
 *
 * <p>对应 Minecraft 26.2 中 {@code BuiltInRegistries.STAT_TYPE} 注册的统计类型，
 * 与磁盘 {@code stats/<uuid>.json} 的外层键一致（如 {@code "minecraft:mined"}）。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class StatsType {

	/** 挖掘（按方块统计）。 */
	public static final String MINED = "minecraft:mined";
	/** 合成（按物品统计）。 */
	public static final String CRAFTED = "minecraft:crafted";
	/** 使用（按物品统计）。 */
	public static final String USED = "minecraft:used";
	/** 损坏（按物品统计）。 */
	public static final String BROKEN = "minecraft:broken";
	/** 拾起（按物品统计）。 */
	public static final String PICKED_UP = "minecraft:picked_up";
	/** 丢弃（按物品统计）。 */
	public static final String DROPPED = "minecraft:dropped";
	/** 击杀（按实体类型统计）。 */
	public static final String KILLED = "minecraft:killed";
	/** 被击杀（按实体类型统计）。 */
	public static final String KILLED_BY = "minecraft:killed_by";
	/** 自定义统计（如移动距离、死亡次数）。 */
	public static final String CUSTOM = "minecraft:custom";

	/** 26.2 所有内置统计类型键。 */
	public static final Set<String> KNOWN_TYPES = Set.of(
		MINED, CRAFTED, USED, BROKEN, PICKED_UP, DROPPED, KILLED, KILLED_BY, CUSTOM
	);

	private StatsType() {
	}

	/** 是否为已知的内置统计类型。 */
	public static boolean isKnown(String typeKey) {
		return KNOWN_TYPES.contains(typeKey);
	}
}
