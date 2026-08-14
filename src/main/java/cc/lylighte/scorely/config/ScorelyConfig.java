package cc.lylighte.scorely.config;

import java.util.List;
import java.util.Map;

import cc.lylighte.scorely.scoring.DefaultRules;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.util.Lang;

/**
 * 服务端配置文件（{@code config/scorely/config.json}）结构。
 *
 * <p>字段即 JSON schema：</p>
 * <ul>
 *   <li>{@code language} —— 服务器默认语言（控制台/无法获取玩家语言时使用），
 *       在线玩家优先跟随自己的客户端语言设置；</li>
 *   <li>{@code lang} —— 语言覆盖表（可选）：翻译键 → {语言码 → 文本}，
 *       查表优先于内置语言表（Phase 9.1），服主可新增自定义键或覆盖内置键；</li>
 *   <li>{@code refreshIntervalMinutes} —— 定时全量重算周期（分钟）；</li>
 *   <li>{@code rules} —— 积分规则列表，条目结构与 {@link ScoringRule} 一一对应，
 *       由 Gson 直接反序列化（无 DTO 副本）。</li>
 * </ul>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class ScorelyConfig {

	/** 服务器默认语言（Phase 9 国际化；缺省 zh_cn）。 */
	private String language = Lang.DEFAULT_LANGUAGE;
	/** 语言覆盖表（Phase 9.1；缺省 null = 不覆盖，仅用内置语言表）。 */
	private Map<String, Map<String, String>> lang;
	/** 刷新周期（分钟），默认 5。 */
	private int refreshIntervalMinutes = DefaultRules.REFRESH_INTERVAL_MINUTES;
	/** 积分规则列表（空列表在加载时视为无效，回退默认规则）。 */
	private List<ScoringRule> rules = List.of();

	/** Gson 反序列化所需的无参构造。 */
	public ScorelyConfig() {
	}

	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}

	public int getRefreshIntervalMinutes() {
		return refreshIntervalMinutes;
	}

	public void setRefreshIntervalMinutes(int refreshIntervalMinutes) {
		this.refreshIntervalMinutes = refreshIntervalMinutes;
	}

	public List<ScoringRule> getRules() {
		return rules;
	}

	public void setRules(List<ScoringRule> rules) {
		this.rules = rules == null ? List.of() : rules;
	}

	public Map<String, Map<String, String>> getLang() {
		return lang;
	}

	public void setLang(Map<String, Map<String, String>> lang) {
		this.lang = lang;
	}
}
