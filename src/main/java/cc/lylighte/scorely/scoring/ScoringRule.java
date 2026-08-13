package cc.lylighte.scorely.scoring;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 积分规则定义。
 *
 * <p>两种计分模式（由 {@code type} 区分）：</p>
 * <ul>
 *   <li>{@code stat}（统计型）：匹配统计项，按 {@code 统计值 × multiplier} 累加计分；</li>
 *   <li>{@code advancement}（进度型）：按已完成进度一次性给分。</li>
 * </ul>
 *
 * <p>字段与 {@code config.json} 的 rules 条目一一对应，由 Gson 反序列化（Phase 8 接入配置管理）。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class ScoringRule {

	/** 计分模式：统计型。 */
	public static final String TYPE_STAT = "stat";
	/** 计分模式：进度型。 */
	public static final String TYPE_ADVANCEMENT = "advancement";

	private String id;
	private String displayName;
	private String type;
	/** stat 型：匹配条件列表。 */
	private List<StatMatcher> matchers = List.of();
	/** stat 型：权重乘数。 */
	private double multiplier = 1.0;
	/** advancement 型：进度 ID → 分值。 */
	private Map<String, Double> advancementValues = Map.of();
	/** advancement 型：未单独配置的进度默认分值。 */
	private double defaultValue;

	/** Gson 反序列化所需的无参构造。 */
	public ScoringRule() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public List<StatMatcher> getMatchers() {
		return matchers;
	}

	public void setMatchers(List<StatMatcher> matchers) {
		this.matchers = matchers == null ? List.of() : matchers;
	}

	public double getMultiplier() {
		return multiplier;
	}

	public void setMultiplier(double multiplier) {
		this.multiplier = multiplier;
	}

	public Map<String, Double> getAdvancementValues() {
		return advancementValues;
	}

	public void setAdvancementValues(Map<String, Double> advancementValues) {
		this.advancementValues = advancementValues == null ? Map.of() : advancementValues;
	}

	public double getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(double defaultValue) {
		this.defaultValue = defaultValue;
	}

	/** 是否为统计型规则。 */
	public boolean isStatType() {
		return TYPE_STAT.equals(type);
	}

	/** 是否为进度型规则。 */
	public boolean isAdvancementType() {
		return TYPE_ADVANCEMENT.equals(type);
	}

	/**
	 * 按本规则计算玩家积分（stat 型）。
	 *
	 * <p>遍历玩家统计表中被 {@code matchers} 命中的条目，累加 {@code 统计值 × multiplier}。</p>
	 *
	 * @param stats 玩家统计键值表（统一键格式 {@code "statType/statPath"}）
	 * @return 积分（无命中或非 stat 型返回 0）
	 */
	public double scoreStat(Map<String, Integer> stats) {
		if (!isStatType() || matchers.isEmpty() || stats == null || stats.isEmpty()) {
			return 0;
		}
		double total = 0;
		for (Map.Entry<String, Integer> entry : stats.entrySet()) {
			for (StatMatcher matcher : matchers) {
				if (matcher.matches(entry.getKey())) {
					total += entry.getValue() * multiplier;
					break;
				}
			}
		}
		return total;
	}

	/**
	 * 按本规则计算玩家积分（advancement 型）。
	 *
	 * <p>遍历玩家已完成的进度：在 {@code advancementValues} 中单独配置的进度给对应分值，
	 * 其余进度给 {@code defaultValue}（默认值为 0 时不加分）。</p>
	 *
	 * @param completedAdvancements 玩家已完成的进度 ID 集合
	 * @return 积分（无完成进度或非 advancement 型返回 0）
	 */
	public double scoreAdvancement(Set<String> completedAdvancements) {
		if (!isAdvancementType() || completedAdvancements == null || completedAdvancements.isEmpty()) {
			return 0;
		}
		double total = 0;
		for (String advancementId : completedAdvancements) {
			Double value = advancementValues.get(advancementId);
			if (value != null) {
				total += value;
			} else if (defaultValue > 0) {
				total += defaultValue;
			}
		}
		return total;
	}
}
