package cc.lylighte.scorely.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cc.lylighte.scorely.stats.StatsType;

/**
 * 内置默认积分规则（Phase 12 预置榜单重做，定稿见 doc/SCORING_PLAN.md）。
 *
 * <p>五榜结构：</p>
 * <ul>
 *   <li>{@code craft} —— 工艺：mined/used/crafted 三类型通配，纯阶段奖励（单条目满 200），
 *       maxScore=800 显式满分；</li>
 *   <li>{@code combat} —— 战斗：killed/* 通配 + player_kills 阶段奖励（cap=0），maxScore=800；</li>
 *   <li>{@code explore} —— 探索：距离 12 键（divisor 100000 cm→km）+ 开箱/钓鱼 5 键阶段奖励，
 *       maxScore=800；</li>
 *   <li>{@code penalty} —— 惩罚：deaths / killed_by（豁免 player）/ damage_taken / PVP 击杀特化，
 *       全部负分档位，sort=asc，maxScore=-800 封底；</li>
 *   <li>{@code advancements} —— 进度：帧分层（task 20 / goal 30 / challenge 60，frameValues），
 *       无 maxScore（进度有限天然封顶）。</li>
 * </ul>
 *
 * <p>displayName 使用翻译键（{@code scorely.rule.*}，Phase 9 国际化）——命令层按玩家语言渲染；
 * config.json 中服主自定义名不回退（字面文本）。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class DefaultRules {

	private DefaultRules() {
	}

	/** 刷新周期默认值（分钟）。Phase 8 由 config.json 的 refreshIntervalMinutes 覆盖。 */
	public static final int REFRESH_INTERVAL_MINUTES = 5;

	/** 通配型榜显式满分（SCORING_PLAN 1.1，统一 800）。 */
	public static final double MAX_SCORE = 800;
	/** 惩罚榜封底（负值语义 = max(自然, -800)，SCORING_PLAN 3.6/3.7，含 PVP 击杀）。 */
	public static final double PENALTY_FLOOR = -800;

	/** 探索榜距离键（custom / …_one_cm，divisor 100000）。 */
	private static final String[] DISTANCE_KEYS = {
		"minecraft:walked_one_cm", "minecraft:sprint_one_cm", "minecraft:fly_one_cm",
		"minecraft:swim_one_cm", "minecraft:boat_one_cm", "minecraft:minecart_one_cm",
		"minecraft:horse_one_cm", "minecraft:aviate_one_cm", "minecraft:strider_one_cm",
		"minecraft:happy_ghast_one_cm", "minecraft:nautilus_one_cm", "minecraft:pig_one_cm"
	};

	/** 探索榜开箱/钓鱼键（次数，divisor 1）。 */
	private static final String[] LOOT_KEYS = {
		"minecraft:open_chest", "minecraft:open_enderchest", "minecraft:open_shulker_box",
		"minecraft:open_barrel", "minecraft:fish_caught"
	};

	/**
	 * 创建内置默认规则列表。
	 *
	 * @return 不可变规则列表
	 */
	public static List<ScoringRule> create() {
		return List.of(craft(), combat(), explore(), penalty(), advancements());
	}

	/** 工艺榜：mined/used/crafted 三类型通配阶段奖励，maxScore=800（SCORING_PLAN 3.1）。 */
	private static ScoringRule craft() {
		ScoringRule rule = new ScoringRule();
		rule.setId("craft");
		rule.setDisplayName("scorely.rule.craft");
		rule.setType(ScoringRule.TYPE_STAT);
		rule.setMaxScore(MAX_SCORE);
		rule.setCap(0); // 纯阶段奖励：默认 cap=1000 会封掉高阈值档位，必须显式关闭
		rule.setMatchers(List.of(
			tiered(StatsType.MINED, StatMatcher.WILDCARD,
				new StatTier(1000, 5), new StatTier(5000, 15), new StatTier(20000, 30),
				new StatTier(50000, 50), new StatTier(100000, 100)),
			tiered(StatsType.USED, StatMatcher.WILDCARD,
				new StatTier(500, 5), new StatTier(2000, 15), new StatTier(10000, 30),
				new StatTier(20000, 50), new StatTier(50000, 100)),
			tiered(StatsType.CRAFTED, StatMatcher.WILDCARD,
				new StatTier(100, 5), new StatTier(500, 15), new StatTier(2000, 30),
				new StatTier(5000, 50), new StatTier(10000, 100))));
		return rule;
	}

	/** 战斗榜：killed/* 通配 + player_kills 阶段奖励，maxScore=800（SCORING_PLAN 3.3）。 */
	private static ScoringRule combat() {
		ScoringRule rule = new ScoringRule();
		rule.setId("combat");
		rule.setDisplayName("scorely.rule.combat");
		rule.setType(ScoringRule.TYPE_STAT);
		rule.setMaxScore(MAX_SCORE);
		rule.setCap(0);
		rule.setMatchers(List.of(
			tiered(StatsType.KILLED, StatMatcher.WILDCARD,
				new StatTier(100, 10), new StatTier(500, 30), new StatTier(1000, 50),
				new StatTier(2500, 100), new StatTier(5000, 200)),
			tiered(StatsType.CUSTOM, "minecraft:player_kills",
				new StatTier(5, 20), new StatTier(20, 50), new StatTier(50, 100),
				new StatTier(100, 200))));
		return rule;
	}

	/** 探索榜：距离 12 键 + 开箱/钓鱼 5 键阶段奖励，maxScore=800（SCORING_PLAN 3.4）。 */
	private static ScoringRule explore() {
		ScoringRule rule = new ScoringRule();
		rule.setId("explore");
		rule.setDisplayName("scorely.rule.exploration");
		rule.setType(ScoringRule.TYPE_STAT);
		rule.setMaxScore(MAX_SCORE);
		rule.setCap(0);
		List<StatMatcher> matchers = new ArrayList<>();
		for (String key : DISTANCE_KEYS) {
			matchers.add(tiered(StatsType.CUSTOM, key, 100_000.0,
				new StatTier(5, 30), new StatTier(40, 50), new StatTier(100, 80),
				new StatTier(400, 120), new StatTier(800, 200)));
		}
		for (String key : LOOT_KEYS) {
			matchers.add(tiered(StatsType.CUSTOM, key,
				new StatTier(50, 30), new StatTier(200, 70), new StatTier(500, 120),
				new StatTier(1000, 200), new StatTier(2000, 300)));
		}
		rule.setMatchers(matchers);
		return rule;
	}

	/** 惩罚榜：四口径负分档位，sort=asc，maxScore=-800 封底（SCORING_PLAN 3.6/3.7）。 */
	private static ScoringRule penalty() {
		ScoringRule rule = new ScoringRule();
		rule.setId("penalty");
		rule.setDisplayName("scorely.rule.penalty");
		rule.setType(ScoringRule.TYPE_STAT);
		rule.setSort(ScoringRule.SORT_ASC);
		rule.setMaxScore(PENALTY_FLOOR);
		rule.setCap(0); // damage_taken 5000 档超过默认 cap 1000，必须显式关闭
		// PVP 被杀豁免：disabled 命中 = 该统计项整体跳过（黑名单语义，须置于通配之前）
		StatMatcher pvpExempt = new StatMatcher(StatsType.KILLED_BY, "minecraft:player");
		pvpExempt.setEnabled(false);
		rule.setMatchers(List.of(
			pvpExempt,
			tiered(StatsType.KILLED_BY, StatMatcher.WILDCARD,
				new StatTier(1, -5), new StatTier(10, -10), new StatTier(50, -20),
				new StatTier(200, -35)),
			tiered(StatsType.CUSTOM, "minecraft:deaths",
				new StatTier(3, -10), new StatTier(10, -15), new StatTier(15, -20),
				new StatTier(20, -40), new StatTier(30, -80)),
			tiered(StatsType.CUSTOM, "minecraft:damage_taken",
				new StatTier(50, -5), new StatTier(100, -10), new StatTier(300, -20),
				new StatTier(1000, -35), new StatTier(5000, -55)),
			tiered(StatsType.KILLED, "minecraft:player",
				new StatTier(1, -20), new StatTier(5, -40), new StatTier(10, -60),
				new StatTier(15, -80), new StatTier(50, -100))));
		return rule;
	}

	/** 进度榜：帧分层（task 20 / goal 30 / challenge 60），无 maxScore（SCORING_PLAN 2.1）。 */
	private static ScoringRule advancements() {
		ScoringRule rule = new ScoringRule();
		rule.setId("advancements");
		rule.setDisplayName("scorely.rule.advancements");
		rule.setType(ScoringRule.TYPE_ADVANCEMENT);
		rule.setFrameValues(Map.of(
			"task", 20.0,
			"goal", 30.0,
			"challenge", 60.0));
		return rule;
	}

	/** 构造阶段奖励匹配项（divisor 默认 1；cap 继承规则级 0）。 */
	private static StatMatcher tiered(String statType, String statPath, StatTier... tiers) {
		return tiered(statType, statPath, 1.0, tiers);
	}

	/** 构造阶段奖励匹配项（显式 divisor；cap 继承规则级 0）。 */
	private static StatMatcher tiered(String statType, String statPath, double divisor, StatTier... tiers) {
		StatMatcher matcher = new StatMatcher(statType, statPath);
		matcher.setDivisor(divisor);
		matcher.setTiers(List.of(tiers));
		return matcher;
	}
}
