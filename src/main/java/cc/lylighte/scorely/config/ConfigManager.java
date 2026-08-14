package cc.lylighte.scorely.config;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cc.lylighte.scorely.Scorely;
import cc.lylighte.scorely.scoring.DefaultRules;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.scoring.StatMatcher;
import cc.lylighte.scorely.scoring.StatTier;
import cc.lylighte.scorely.util.Lang;
import cc.lylighte.scorely.util.Result;

import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 服务端配置管理（Phase 8）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li><strong>config.json</strong>：首次启动生成默认配置（序列化 {@link DefaultRules}）；
 *       加载 + 校验（id 唯一 / type 合法 / 数值非负 / divisor ≠ 0 / tiers 非负）；
 *       解析失败或校验失败 → 回退默认规则并保留 {@code .bak} 副本（不崩溃）；</li>
 *   <li><strong>players.json</strong>：玩家 UUID → 显示名 缓存（在线查询时的零成本补充，
 *       离线展示用；Gson 限制用 String key），脏标记 + 周期兜底落盘 + 服务器停止落盘，
 *       原子写防损坏。</li>
 * </ul>
 *
 * <p>配置文件位于 {@code config/scorely/} 目录（Fabric 惯例）。</p>
 *
 * <p><strong>线程约束</strong>：除 {@link #load()} 在服务器启动回调中执行外，
 * 其余方法仅在服务器线程（tick / 命令）调用。</p>
 */
public final class ConfigManager {

	/** 配置文件目录名。 */
	private static final String CONFIG_FILE = "config.json";
	/** 玩家名称缓存文件名。 */
	private static final String PLAYERS_FILE = "players.json";
	/** 损坏配置备份后缀。 */
	private static final String BACKUP_SUFFIX = ".bak";
	/** 名称缓存兜底落盘周期（tick）：5 分钟。 */
	private static final long AUTO_SAVE_TICKS = 20L * 60 * 5;

	/** 名称缓存 JSON 类型（Map&lt;String, String&gt;，Gson 对非 String key 支持不稳定）。 */
	private static final Type PLAYER_NAMES_TYPE = new TypeToken<Map<String, String>>() { }.getType();

	private final Path configDir;
	/** 积分规则（加载后生效；加载失败时保持默认）。 */
	private List<ScoringRule> rules = DefaultRules.create();
	/** 刷新周期（分钟）。 */
	private int refreshIntervalMinutes = DefaultRules.REFRESH_INTERVAL_MINUTES;
	/** 玩家 UUID → 显示名（名称缓存，String key 读写）。 */
	private final Map<UUID, String> playerNames = new HashMap<>();
	/** 名称缓存是否变更（脏标记）。 */
	private boolean namesDirty;
	/** tick 计数（兜底落盘用）。 */
	private long tickCounter;

	/** 默认构造：使用 Fabric 配置目录（{@code config/scorely/}）。 */
	public ConfigManager() {
		this(FabricLoader.getInstance().getConfigDir().resolve(Scorely.MOD_ID));
	}

	/** 指定配置目录（测试用）。 */
	ConfigManager(Path configDir) {
		this.configDir = configDir;
	}

	/**
	 * 加载配置（服务器启动回调中调用，先于调度器首刷）。
	 *
	 * <p>config.json 不存在 → 生成默认；存在 → 加载并校验，任何失败回退默认
	 * （原文件保留为 {@code .bak}），服务器照常启动。</p>
	 */
	public void load() {
		Path configPath = configDir.resolve(CONFIG_FILE);
		if (!Config.exists(configPath)) {
			ScorelyConfig defaults = new ScorelyConfig();
			defaults.setRules(DefaultRules.create());
			try {
				Config.save(configPath, defaults);
				Scorely.LOGGER.info("Scorely first launch: generated default config {}", configPath);
			} catch (IOException e) {
				Scorely.LOGGER.warn("Scorely failed to generate default config (using built-in default rules): {}", e.toString());
			}
			loadPlayerNames();
			return;
		}

		try {
			ScorelyConfig config = Config.load(configPath, ScorelyConfig.class);
			Result validation = validate(config);
			if (validation != null) {
				throw new IllegalArgumentException(validation.getKey());
			}
			this.rules = List.copyOf(config.getRules());
			this.refreshIntervalMinutes = config.getRefreshIntervalMinutes() > 0
					? config.getRefreshIntervalMinutes()
					: DefaultRules.REFRESH_INTERVAL_MINUTES;
			Lang.setDefaultLanguage(config.getLanguage());
			Lang.setOverrides(config.getLang());
			Scorely.LOGGER.info("Scorely config loaded: {} rules, refresh interval {} min, default language {}",
					rules.size(), refreshIntervalMinutes, config.getLanguage());
		} catch (Exception e) {
			backupInvalid(configPath);
			Scorely.LOGGER.warn("Scorely config load failed (falling back to default rules, original kept as {}.bak): {}",
					configPath, e.toString());
		}
		loadPlayerNames();
	}

	/**
	 * 热重载配置（{@code /scorely admin reload} 调用）。
	 *
	 * <p>与启动加载语义不同：任何失败（文件缺失/解析失败/校验失败）<strong>不修改当前生效配置</strong>，
	 * 不生成备份（文件保持原样），由命令层提示错误；全部校验通过后才原子替换规则与间隔。</p>
	 *
	 * @return 成功（携带摘要）或失败（携带原因）；调用方需自行应用 {@link #getRules()} 并触发重算
	 */
	public Result reload() {
		Path configPath = configDir.resolve(CONFIG_FILE);
		if (!Config.exists(configPath)) {
			return Result.failure("config.reload.missing", configPath);
		}
		try {
			ScorelyConfig config = Config.load(configPath, ScorelyConfig.class);
			Result validation = validate(config);
			if (validation != null) {
				return validation; // 错误 key 已具体，命令层按玩家语言渲染
			}
			List<ScoringRule> newRules = List.copyOf(config.getRules());
			int newInterval = config.getRefreshIntervalMinutes() > 0
					? config.getRefreshIntervalMinutes()
					: DefaultRules.REFRESH_INTERVAL_MINUTES;
			// 全部校验通过后才替换状态（失败保持旧配置生效）
			this.rules = newRules;
			this.refreshIntervalMinutes = newInterval;
			Lang.setDefaultLanguage(config.getLanguage());
			Lang.setOverrides(config.getLang());
			Scorely.LOGGER.info("Scorely config reloaded: {} rules, refresh interval {} min, default language {}",
					rules.size(), refreshIntervalMinutes, config.getLanguage());
			return Result.success("config.reload.ok", rules.size(), refreshIntervalMinutes);
		} catch (Exception e) {
			Scorely.LOGGER.warn("Scorely config reload failed (current config kept active): {}", e.toString());
			return Result.failure("config.reload.parse_error", String.valueOf(e.getMessage()));
		}
	}

	/** 积分规则（不可变）。 */
	public List<ScoringRule> getRules() {
		return rules;
	}

	/** 刷新周期（分钟）。 */
	public int getRefreshIntervalMinutes() {
		return refreshIntervalMinutes;
	}

	/**
	 * 记录玩家显示名（玩家加入事件调用；无变化不置脏）。
	 *
	 * @param uuid 玩家 UUID
	 * @param name 玩家显示名
	 */
	public void updatePlayerName(UUID uuid, String name) {
		if (uuid == null || name == null || name.isEmpty()) {
			return;
		}
		if (!name.equals(playerNames.get(uuid))) {
			playerNames.put(uuid, name);
			namesDirty = true;
		}
	}

	/**
	 * 查询玩家显示名缓存。
	 *
	 * @param uuid 玩家 UUID
	 * @return 缓存的名字；未记录返回 null（Phase 8.2 接入命令展示）
	 */
	public String getPlayerName(UUID uuid) {
		return uuid == null ? null : playerNames.get(uuid);
	}

	/** 名称缓存是否有待落盘的变更。 */
	public boolean isNamesDirty() {
		return namesDirty;
	}

	/**
	 * 周期兜底落盘（每 tick 调用，内部按 {@link #AUTO_SAVE_TICKS} 间隔检查脏标记）。
	 */
	public void onServerTick() {
		if (++tickCounter % AUTO_SAVE_TICKS == 0) {
			savePlayersIfDirty();
		}
	}

	/** 脏标记落盘（服务器停止时调用；无变更则零开销）。 */
	public void savePlayersIfDirty() {
		if (!namesDirty) {
			return;
		}
		try {
			Config.saveAtomic(configDir.resolve(PLAYERS_FILE), toJsonMap(playerNames));
			namesDirty = false;
			Scorely.LOGGER.debug("Scorely player name cache saved ({} entries)", playerNames.size());
		} catch (IOException e) {
			Scorely.LOGGER.warn("Scorely failed to save player name cache: {}", e.toString());
		}
	}

	/**
	 * 校验配置内容，返回失败结果（携带错误翻译键与参数）；null 表示通过。
	 */
	private static Result validate(ScorelyConfig config) {
		if (config == null || config.getRules() == null || config.getRules().isEmpty()) {
			return Result.failure("config.error.rules_empty");
		}
		Set<String> ids = new HashSet<>();
		for (ScoringRule rule : config.getRules()) {
			if (rule == null) {
				return Result.failure("config.error.null_rule");
			}
			if (isBlank(rule.getId())) {
				return Result.failure("config.error.id_blank");
			}
			if (!ids.add(rule.getId())) {
				return Result.failure("config.error.id_duplicate", rule.getId());
			}
			if (isBlank(rule.getType())) {
				return Result.failure("config.error.type_missing", rule.getId());
			}
			if (!ScoringRule.TYPE_STAT.equals(rule.getType())
					&& !ScoringRule.TYPE_ADVANCEMENT.equals(rule.getType())) {
				return Result.failure("config.error.type_invalid", rule.getId(), rule.getType());
			}
			if (!isFiniteNonNegative(rule.getMultiplier())) {
				return Result.failure("config.error.multiplier", rule.getId());
			}
			if (!isFiniteNonNegative(rule.getCap())) {
				return Result.failure("config.error.cap", rule.getId());
			}
			if (rule.getDivisor() == 0 || !Double.isFinite(rule.getDivisor())) {
				return Result.failure("config.error.divisor", rule.getId());
			}
			if (!isFiniteNonNegative(rule.getDefaultValue())) {
				return Result.failure("config.error.default_value", rule.getId());
			}
			for (StatMatcher matcher : rule.getMatchers()) {
				if (matcher == null) {
					return Result.failure("config.error.matcher_null", rule.getId());
				}
				Result mError = validateMatcher(rule, matcher);
				if (mError != null) {
					return mError;
				}
			}
			for (StatTier tier : rule.getTiers()) {
				if (tier == null) {
					return Result.failure("config.error.tier_null", rule.getId());
				}
				if (!isFiniteNonNegative(tier.getThreshold()) || !isFiniteNonNegative(tier.getValue())) {
					return Result.failure("config.error.tier_value", rule.getId());
				}
			}
		}
		if (config.getRefreshIntervalMinutes() <= 0) {
			return Result.failure("config.error.interval");
		}
		// Phase 9.1：lang 覆盖表结构校验（键非空、至少一个非空语言文本）
		Map<String, Map<String, String>> lang = config.getLang();
		if (lang != null) {
			for (Map.Entry<String, Map<String, String>> entry : lang.entrySet()) {
				if (isBlank(entry.getKey())) {
					return Result.failure("config.error.lang_key_blank");
				}
				Map<String, String> byLocale = entry.getValue();
				if (byLocale == null || byLocale.isEmpty()) {
					return Result.failure("config.error.lang_value_blank", entry.getKey());
				}
				for (Map.Entry<String, String> text : byLocale.entrySet()) {
					if (isBlank(text.getKey()) || isBlank(text.getValue())) {
						return Result.failure("config.error.lang_value_blank", entry.getKey());
					}
				}
			}
		}
		return null;
	}

	private static Result validateMatcher(ScoringRule rule, StatMatcher matcher) {
		if (matcher.getDivisor() != null && (matcher.getDivisor() == 0 || !Double.isFinite(matcher.getDivisor()))) {
			return Result.failure("config.error.matcher_divisor", rule.getId());
		}
		if (matcher.getCap() != null && !isFiniteNonNegative(matcher.getCap())) {
			return Result.failure("config.error.matcher_cap", rule.getId());
		}
		if (matcher.getMultiplier() != null && !isFiniteNonNegative(matcher.getMultiplier())) {
			return Result.failure("config.error.matcher_multiplier", rule.getId());
		}
		if (matcher.getTiers() != null) {
			for (StatTier tier : matcher.getTiers()) {
				if (tier == null) {
					return Result.failure("config.error.matcher_tier_null", rule.getId());
				}
				if (!isFiniteNonNegative(tier.getThreshold()) || !isFiniteNonNegative(tier.getValue())) {
					return Result.failure("config.error.matcher_tier_value", rule.getId());
				}
			}
		}
		return null;
	}

	/** 数值有限且非负。 */
	private static boolean isFiniteNonNegative(double value) {
		return Double.isFinite(value) && value >= 0;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** 损坏/校验失败的配置：改名保留副本，便于服主事后排查。 */
	private static void backupInvalid(Path configPath) {
		try {
			if (Files.isRegularFile(configPath)) {
				Files.move(configPath, configPath.resolveSibling(configPath.getFileName() + BACKUP_SUFFIX),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			Scorely.LOGGER.warn("Scorely failed to back up corrupted config: {}", e.toString());
		}
	}

	/** 加载玩家名称缓存（文件不存在或损坏则静默忽略，仅记日志）。 */
	private void loadPlayerNames() {
		Path path = configDir.resolve(PLAYERS_FILE);
		if (!Config.exists(path)) {
			return;
		}
		try {
			Map<String, String> raw = Config.load(path, PLAYER_NAMES_TYPE);
			if (raw == null) {
				return;
			}
			playerNames.clear();
			for (Map.Entry<String, String> entry : raw.entrySet()) {
				try {
					playerNames.put(UUID.fromString(entry.getKey()), entry.getValue());
				} catch (IllegalArgumentException ignored) {
					// 非 UUID key（手改文件），跳过
				}
			}
			Scorely.LOGGER.info("Scorely player name cache loaded: {} entries", playerNames.size());
		} catch (Exception e) {
			Scorely.LOGGER.warn("Scorely failed to load player name cache (will re-accumulate): {}", e.toString());
		}
	}

	/** UUID key → String key（Gson 序列化限制）。 */
	private static Map<String, String> toJsonMap(Map<UUID, String> source) {
		Map<String, String> result = new HashMap<>(source.size());
		for (Map.Entry<UUID, String> entry : source.entrySet()) {
			result.put(entry.getKey().toString(), entry.getValue());
		}
		return result;
	}
}
