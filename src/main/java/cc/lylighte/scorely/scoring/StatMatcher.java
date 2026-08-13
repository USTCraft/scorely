package cc.lylighte.scorely.scoring;

import java.util.List;

/**
 * 统计项匹配器（stat 型规则的匹配条件与计分配置）。
 *
 * <p>匹配统一统计键 {@code "statType/statPath"}（如 {@code "minecraft:mined/minecraft:stone"}）：
 * {@code statType} 精确匹配，{@code statPath} 精确匹配或使用 {@code "*"} 通配全部。</p>
 *
 * <p>同时携带该统计项的计分配置（enabled / multiplier / cap / divisor / tiers）。
 * 包装类型字段（{@code null}）表示未配置，计分时继承规则级默认值
 * （见 {@link ScoringRule}）。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class StatMatcher {

	/** 通配符：匹配该类型下的所有统计项。 */
	public static final String WILDCARD = "*";

	/** 分隔符：统一统计键 {@code statType + "/" + statPath}。 */
	public static final String SEPARATOR = "/";

	private String statType;
	private String statPath;

	/** 开关（null = 继承规则默认值）。 */
	private Boolean enabled;
	/** 线性计分倍率（null = 继承规则默认值）。 */
	private Double multiplier;
	/** 封顶值（统计值 ÷ divisor 后单位；null = 继承规则默认值）。 */
	private Double cap;
	/** 单位换算（null = 继承规则默认值）。 */
	private Double divisor;
	/** 阶段奖励档位（null = 继承规则默认值）。 */
	private List<StatTier> tiers;

	/** Gson 反序列化所需的无参构造。 */
	public StatMatcher() {
	}

	/**
	 * 直接构造（仅匹配条件，计分配置留空）。
	 *
	 * @param statType 统计类型键（如 {@code "minecraft:mined"}）
	 * @param statPath 统计项路径（如 {@code "minecraft:stone"} 或 {@code "*"}）
	 */
	public StatMatcher(String statType, String statPath) {
		this.statType = statType;
		this.statPath = statPath;
	}

	public String getStatType() {
		return statType;
	}

	public void setStatType(String statType) {
		this.statType = statType;
	}

	public String getStatPath() {
		return statPath;
	}

	public void setStatPath(String statPath) {
		this.statPath = statPath;
	}

	public Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	public Double getMultiplier() {
		return multiplier;
	}

	public void setMultiplier(Double multiplier) {
		this.multiplier = multiplier;
	}

	public Double getCap() {
		return cap;
	}

	public void setCap(Double cap) {
		this.cap = cap;
	}

	public Double getDivisor() {
		return divisor;
	}

	public void setDivisor(Double divisor) {
		this.divisor = divisor;
	}

	public List<StatTier> getTiers() {
		return tiers;
	}

	public void setTiers(List<StatTier> tiers) {
		this.tiers = tiers;
	}

	/**
	 * 判断该匹配器是否命中给定的统一统计键。
	 *
	 * @param key 统一统计键，格式 {@code "statType/statPath"}
	 * @return 命中返回 {@code true}；键格式非法或类型不匹配返回 {@code false}
	 */
	public boolean matches(String key) {
		if (key == null || statType == null || statPath == null) {
			return false;
		}
		int slash = key.indexOf(SEPARATOR);
		if (slash <= 0 || slash == key.length() - 1) {
			return false;
		}
		if (!statType.equals(key.substring(0, slash))) {
			return false;
		}
		String keyPath = key.substring(slash + 1);
		return WILDCARD.equals(statPath) || statPath.equals(keyPath);
	}

	@Override
	public String toString() {
		return statType + SEPARATOR + statPath;
	}
}
