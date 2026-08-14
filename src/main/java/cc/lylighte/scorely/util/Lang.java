package cc.lylighte.scorely.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import cc.lylighte.scorely.Scorely;
import cc.lylighte.scorely.scoring.ScoringRule;

/**
 * 语言表（Phase 9 国际化）。
 *
 * <p>服务端持有全部语言资源（jar 内 {@code assets/scorely/lang/*.json}），
 * 命令层按目标玩家语言解析消息文本后以字面组件发送——原版客户端无需
 * 安装任何资源包/模组即可获得本地化体验（translatable 组件在纯原版
 * 客户端会直接显示 key，故不采用）。</p>
 *
 * <p>回退链：目标语言 → {@link #FALLBACK_LANGUAGE en_us} → key 原文。</p>
 *
 * <p>纯 Java 实现（ClassLoader 读资源 + Gson 解析），不依赖 Minecraft 类型；
 * 玩家客户端语言来源见 {@link cc.lylighte.scorely.compat.CompatHelper#languageOf}。</p>
 */
public final class Lang {

	/** 默认语言（config.json language 字段缺省值）。 */
	public static final String DEFAULT_LANGUAGE = "zh_cn";
	/** 兜底语言（任何语言缺失 key 时回退）。 */
	public static final String FALLBACK_LANGUAGE = "en_us";
	/** 规则名翻译键前缀：displayName 以此开头视为翻译键，否则视为字面文本（服主自定义名）。 */
	public static final String RULE_KEY_PREFIX = "scorely.rule.";

	/** 支持的语言（jar 内资源目录不可枚举，语言列表在此注册）。 */
	private static final List<String> SUPPORTED_LANGUAGES = List.of(FALLBACK_LANGUAGE, DEFAULT_LANGUAGE);

	/** 语言表（locale → key → 文本）。加载完成后只读。 */
	private static final Map<String, Map<String, String>> TABLES = new HashMap<>();
	/** 服务器默认语言（config.json 加载后覆盖）。 */
	private static volatile String defaultLanguage = DEFAULT_LANGUAGE;
	/** 是否已加载（幂等）。 */
	private static volatile boolean loaded;

	private static final Gson GSON = new Gson();
	private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

	private Lang() {
	}

	/** 加载全部语言表（入口初始化时调用，幂等）。 */
	public static void load() {
		if (loaded) {
			return;
		}
		synchronized (Lang.class) {
			if (loaded) {
				return;
			}
			for (String language : SUPPORTED_LANGUAGES) {
				loadLanguage(language);
			}
			loaded = true;
		}
	}

	/** 设置服务器默认语言（config.json 的 language 字段；空白值忽略）。 */
	public static void setDefaultLanguage(String language) {
		if (language != null && !language.isBlank()) {
			defaultLanguage = language;
		}
	}

	/** 服务器默认语言（控制台/无法获取玩家语言时的缺省值）。 */
	public static String getDefaultLanguage() {
		return defaultLanguage;
	}

	/**
	 * 解析消息：目标语言 → en_us → key 原文，{@code {0}}/{@code {1}} 占位符按序替换。
	 *
	 * @param language 目标语言（如 {@code zh_cn} / {@code en_us}；null 直接回退）
	 * @param key      翻译键
	 * @param args     占位符参数
	 * @return 解析后的文本（永不返回 null）
	 */
	public static String format(String language, String key, Object... args) {
		String text = lookup(language, key);
		if (text == null) {
			text = lookup(FALLBACK_LANGUAGE, key);
		}
		if (text == null) {
			text = key;
		}
		if (args == null || args.length == 0) {
			return text;
		}
		String result = text;
		for (int i = 0; i < args.length; i++) {
			result = result.replace("{" + i + "}", String.valueOf(args[i]));
		}
		return result;
	}

	/**
	 * 规则显示名：displayName 以 {@link #RULE_KEY_PREFIX} 开头视为翻译键，
	 * 否则按字面文本返回（服主自定义名不回退；兼容老配置中的中文榜名）。
	 */
	public static String ruleName(String language, ScoringRule rule) {
		if (rule == null) {
			return "";
		}
		String displayName = rule.getDisplayName();
		if (displayName == null) {
			return rule.getId();
		}
		if (displayName.startsWith(RULE_KEY_PREFIX)) {
			return format(language, displayName);
		}
		return displayName;
	}

	/** 加载单个语言文件（缺失/损坏仅告警，不中断启动）。 */
	private static void loadLanguage(String language) {
		String path = "/assets/scorely/lang/" + language + ".json";
		try (InputStream in = Lang.class.getResourceAsStream(path)) {
			if (in == null) {
				Scorely.LOGGER.warn("Scorely 缺少语言资源: {}", path);
				return;
			}
			Map<String, String> table = GSON.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), STRING_MAP_TYPE);
			if (table == null) {
				Scorely.LOGGER.warn("Scorely 语言资源解析为空: {}", path);
				return;
			}
			TABLES.put(language, table);
			Scorely.LOGGER.debug("Scorely 语言表加载成功: {} ({} 键)", language, table.size());
		} catch (IOException e) {
			Scorely.LOGGER.warn("Scorely 语言资源加载失败 {}: {}", path, e.toString());
		}
	}

	/** 查表（语言不存在或 key 缺失返回 null）。 */
	private static String lookup(String language, String key) {
		if (language == null || key == null) {
			return null;
		}
		Map<String, String> table = TABLES.get(language);
		return table == null ? null : table.get(key);
	}
}
