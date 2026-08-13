package cc.lylighte.scorely.scoring;

/**
 * 阶段奖励档位（里程碑计分）。
 *
 * <p>当玩家统计值（经 {@code divisor} 换算后）达到 {@code threshold} 时，获得 {@code value} 分。
 * 所有达到的档位分值累加——例如 {@code [{10→30}, {40→40}, {100→50}]}，
 * 统计值为 100 时得分 30 + 40 + 50 = 120。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class StatTier {

	/** 档位阈值（统计值 ÷ divisor 后的单位）。 */
	private double threshold;
	/** 达到该档位获得的奖励分。 */
	private double value;

	/** Gson 反序列化所需的无参构造。 */
	public StatTier() {
	}

	/**
	 * 直接构造。
	 *
	 * @param threshold 档位阈值
	 * @param value     达到该档位的奖励分
	 */
	public StatTier(double threshold, double value) {
		this.threshold = threshold;
		this.value = value;
	}

	public double getThreshold() {
		return threshold;
	}

	public void setThreshold(double threshold) {
		this.threshold = threshold;
	}

	public double getValue() {
		return value;
	}

	public void setValue(double value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return "StatTier{threshold=" + threshold + ", value=" + value + "}";
	}
}
