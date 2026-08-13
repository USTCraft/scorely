package cc.lylighte.scorely.scoring;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 积分规则定义。
 *
 * <p>两种计分模式（由 {@code type} 区分）：</p>
 * <ul>
 *   <li>{@code stat}（统计型）：匹配统计项计分，支持线性（{@code 统计值 × multiplier}）与
 *       阶段（{@code tiers} 阈值累加）两种方式；</li>
 *   <li>{@code advancement}（进度型）：按已完成进度一次性给分。</li>
 * </ul>
 *
 * <p>stat 型的计分配置（enabled / multiplier / cap / divisor / tiers）在规则级提供默认值，
 * 匹配项（{@link StatMatcher}）可逐项覆盖——规则配置了 {@code tiers} 时按阶段计分，
 * 否则按 {@code multiplier} 线性计分。</p>
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
	/** stat 型：匹配条件与计分配置列表。 */
	private List<StatMatcher> matchers = List.of();
	/** stat 型默认值：开关。 */
	private boolean enabled = true;
	/** stat 型默认值：线性计分倍率。 */
	private double multiplier = 1.0;
	/** stat 型默认值：封顶值（统计值 ÷ divisor 后单位；默认 1000，显式配 0 = 不封顶）。 */
	private double cap = 1000.0;
	/** stat 型默认值：单位换算（统计值 ÷ divisor 后与档位阈值/封顶值比较）。 */
	private double divisor = 1.0;
	/** stat 型默认值：阶段奖励档位（非空时按阶段计分，忽略 multiplier）。 */
	private List<StatTier> tiers = List.of();
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

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public double getMultiplier() {
		return multiplier;
	}

	public void setMultiplier(double multiplier) {
		this.multiplier = multiplier;
	}

	public double getCap() {
		return cap;
	}

	public void setCap(double cap) {
		this.cap = cap;
	}

	public double getDivisor() {
		return divisor;
	}

	public void setDivisor(double divisor) {
		this.divisor = divisor;
	}

	public List<StatTier> getTiers() {
		return tiers;
	}

	public void setTiers(List<StatTier> tiers) {
		this.tiers = tiers == null ? List.of() : tiers;
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
	 * <p>每个命中的统计项独立计分后求和：匹配项关闭（enabled=false）则跳过且不阻塞后续匹配项；
	 * 第一个命中且启用的匹配项负责计分。计分方式见 {@link #scoreMatcher}。</p>
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
				if (!matcher.matches(entry.getKey())) {
					continue;
				}
				if (!effectiveEnabled(matcher)) {
					continue;
				}
				total += scoreMatcher(matcher, entry.getValue());
				break;
			}
		}
		return total;
	}

	/**
	 * 按匹配项的计分配置计算单个统计值的积分。
	 *
	 * <p>有效配置 = 匹配项覆盖规则级默认值（null 继承）。两种模式：</p>
	 * <ul>
	 *   <li>阶段计分（tiers 非空）：{@code adjusted = min(值 ÷ divisor, cap)}，
	 *       累加所有 {@code threshold ≤ adjusted} 的档位分值；</li>
	 *   <li>线性计分：{@code min(值, cap × divisor) × multiplier}。</li>
	 * </ul>
	 *
	 * @param matcher  命中的匹配项
	 * @param rawValue 统计值（原始单位）
	 * @return 该统计值的积分
	 */
	private double scoreMatcher(StatMatcher matcher, int rawValue) {
		double effectiveDivisor = matcher.getDivisor() != null ? matcher.getDivisor() : divisor;
		double effectiveCap = matcher.getCap() != null ? matcher.getCap() : cap;
		List<StatTier> effectiveTiers = matcher.getTiers() != null ? matcher.getTiers() : tiers;

		if (!effectiveTiers.isEmpty()) {
			double adjusted = rawValue / effectiveDivisor;
			if (effectiveCap > 0 && adjusted > effectiveCap) {
				adjusted = effectiveCap;
			}
			double score = 0;
			for (StatTier tier : effectiveTiers) {
				if (tier.getThreshold() <= adjusted) {
					score += tier.getValue();
				}
			}
			return score;
		}

		double effectiveValue = rawValue;
		if (effectiveCap > 0) {
			double capRaw = effectiveCap * effectiveDivisor;
			if (effectiveValue > capRaw) {
				effectiveValue = capRaw;
			}
		}
		double effectiveMultiplier = matcher.getMultiplier() != null ? matcher.getMultiplier() : multiplier;
		return effectiveValue * effectiveMultiplier;
	}

	/** 匹配项是否启用（null 继承规则默认值）。 */
	private boolean effectiveEnabled(StatMatcher matcher) {
		return matcher.getEnabled() == null ? enabled : matcher.getEnabled();
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
