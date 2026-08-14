package cc.lylighte.scorely.scoring;

import java.util.List;
import java.util.Map;

/**
 * 内置默认积分规则（技术占位）。
 *
 * <p>默认积分策略尚未整体定稿（用户将单独规划），此处规则仅为让 Phase 7 事件层
 * 接入后可实际计分验证，不应视为最终策略。Phase 8 接入 {@code config.json} 后，
 * 服务端配置的 rules 将替换本类产出。</p>
 *
 * <p>规则结构与 doc/PLAN.md 配置示例一致：</p>
 * <ul>
 *   <li>{@code mining} —— 挖掘：mined/* 线性 ×1.0；</li>
 *   <li>{@code combat} —— 战斗：killed/* 线性 ×2.0；</li>
 *   <li>{@code exploration} —— 探索：步行阶段档位（divisor 100000，10km→30 分，
 *       40km→40 分，100km→50 分，封顶 200），飞行禁用，坐船线性 ×0.0005；</li>
 *   <li>{@code advancements} —— 进度：未单独配置的进度默认 10 分，
 *       关键进度额外加分。</li>
 * </ul>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class DefaultRules {

	private DefaultRules() {
	}

	/** 刷新周期默认值（分钟）。Phase 8 由 config.json 的 refreshInterval 覆盖。 */
	public static final int REFRESH_INTERVAL_MINUTES = 5;

	/**
	 * 创建内置默认规则列表。
	 *
	 * <p>displayName 使用翻译键（{@code scorely.rule.*}，Phase 9 国际化）——
	 * 命令层按玩家语言渲染；config.json 中服主自定义名不回退（字面文本）。</p>
	 *
	 * @return 不可变规则列表
	 */
	public static List<ScoringRule> create() {
		return List.of(mining(), combat(), exploration(), advancements());
	}

	private static ScoringRule mining() {
		ScoringRule rule = new ScoringRule();
		rule.setId("mining");
		rule.setDisplayName("scorely.rule.mining");
		rule.setType(ScoringRule.TYPE_STAT);
		rule.setMatchers(List.of(new StatMatcher("minecraft:mined", StatMatcher.WILDCARD)));
		rule.setMultiplier(1.0);
		return rule;
	}

	private static ScoringRule combat() {
		ScoringRule rule = new ScoringRule();
		rule.setId("combat");
		rule.setDisplayName("scorely.rule.combat");
		rule.setType(ScoringRule.TYPE_STAT);
		rule.setMatchers(List.of(new StatMatcher("minecraft:killed", StatMatcher.WILDCARD)));
		rule.setMultiplier(2.0);
		return rule;
	}

	private static ScoringRule exploration() {
		ScoringRule rule = new ScoringRule();
		rule.setId("exploration");
		rule.setDisplayName("scorely.rule.exploration");
		rule.setType(ScoringRule.TYPE_STAT);

		StatMatcher walked = new StatMatcher("minecraft:custom", "minecraft:walked_one_cm");
		walked.setDivisor(100_000.0); // cm → km
		walked.setTiers(List.of(
				new StatTier(10, 30),
				new StatTier(40, 40),
				new StatTier(100, 50)));
		walked.setCap(200.0);

		StatMatcher fly = new StatMatcher("minecraft:custom", "minecraft:fly_one_cm");
		fly.setEnabled(false);

		StatMatcher boat = new StatMatcher("minecraft:custom", "minecraft:boat_one_cm");
		boat.setMultiplier(0.0005);

		rule.setMatchers(List.of(walked, fly, boat));
		return rule;
	}

	private static ScoringRule advancements() {
		ScoringRule rule = new ScoringRule();
		rule.setId("advancements");
		rule.setDisplayName("scorely.rule.advancements");
		rule.setType(ScoringRule.TYPE_ADVANCEMENT);
		rule.setDefaultValue(10.0);
		rule.setAdvancementValues(Map.of(
				"minecraft:adventure/kill_all_mobs", 100.0,
				"minecraft:end/kill_dragon", 200.0,
				"minecraft:nether/all_effects", 150.0));
		return rule;
	}
}
