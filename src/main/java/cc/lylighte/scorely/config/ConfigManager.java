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
				Scorely.LOGGER.info("Scorely 首次启动：已生成默认配置 {}", configPath);
			} catch (IOException e) {
				Scorely.LOGGER.warn("Scorely 生成默认配置失败（使用内置默认规则）: {}", e.toString());
			}
			loadPlayerNames();
			return;
		}

		try {
			ScorelyConfig config = Config.load(configPath, ScorelyConfig.class);
			String error = validate(config);
			if (error != null) {
				throw new IllegalArgumentException(error);
			}
			this.rules = List.copyOf(config.getRules());
			this.refreshIntervalMinutes = config.getRefreshIntervalMinutes() > 0
					? config.getRefreshIntervalMinutes()
					: DefaultRules.REFRESH_INTERVAL_MINUTES;
			Scorely.LOGGER.info("Scorely 配置加载成功：{} 条规则，刷新周期 {} 分钟",
					rules.size(), refreshIntervalMinutes);
		} catch (Exception e) {
			backupInvalid(configPath);
			Scorely.LOGGER.warn("Scorely 配置加载失败（回退默认规则，原文件已保留为 {}.bak）: {}",
					configPath, e.toString());
		}
		loadPlayerNames();
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
			Scorely.LOGGER.debug("Scorely 玩家名称缓存已落盘（{} 条）", playerNames.size());
		} catch (IOException e) {
			Scorely.LOGGER.warn("Scorely 玩家名称缓存落盘失败: {}", e.toString());
		}
	}

	/** 校验配置内容，返回错误描述；null 表示通过。 */
	private static String validate(ScorelyConfig config) {
		if (config == null || config.getRules() == null || config.getRules().isEmpty()) {
			return "rules 不能为空";
		}
		Set<String> ids = new HashSet<>();
		for (ScoringRule rule : config.getRules()) {
			if (rule == null) {
				return "存在空规则条目";
			}
			if (isBlank(rule.getId())) {
				return "规则 id 不能为空";
			}
			if (!ids.add(rule.getId())) {
				return "规则 id 重复: " + rule.getId();
			}
			if (isBlank(rule.getType())) {
				return "规则 " + rule.getId() + " 缺少 type";
			}
			if (!ScoringRule.TYPE_STAT.equals(rule.getType())
					&& !ScoringRule.TYPE_ADVANCEMENT.equals(rule.getType())) {
				return "规则 " + rule.getId() + " 的 type 非法: " + rule.getType();
			}
			if (!isFiniteNonNegative(rule.getMultiplier())) {
				return "规则 " + rule.getId() + " 的 multiplier 必须为非负数值";
			}
			if (!isFiniteNonNegative(rule.getCap())) {
				return "规则 " + rule.getId() + " 的 cap 必须为非负数值";
			}
			if (rule.getDivisor() == 0 || !Double.isFinite(rule.getDivisor())) {
				return "规则 " + rule.getId() + " 的 divisor 必须为非零数值";
			}
			if (!isFiniteNonNegative(rule.getDefaultValue())) {
				return "规则 " + rule.getId() + " 的 defaultValue 必须为非负数值";
			}
			for (StatMatcher matcher : rule.getMatchers()) {
				if (matcher == null) {
					return "规则 " + rule.getId() + " 存在空匹配项";
				}
				String mError = validateMatcher(rule, matcher);
				if (mError != null) {
					return mError;
				}
			}
			for (StatTier tier : rule.getTiers()) {
				if (tier == null) {
					return "规则 " + rule.getId() + " 存在空档位";
				}
				if (!isFiniteNonNegative(tier.getThreshold()) || !isFiniteNonNegative(tier.getValue())) {
					return "规则 " + rule.getId() + " 的档位 threshold/value 必须为非负数值";
				}
			}
		}
		if (config.getRefreshIntervalMinutes() <= 0) {
			return "refreshIntervalMinutes 必须为正整数";
		}
		return null;
	}

	private static String validateMatcher(ScoringRule rule, StatMatcher matcher) {
		if (matcher.getDivisor() != null && (matcher.getDivisor() == 0 || !Double.isFinite(matcher.getDivisor()))) {
			return "规则 " + rule.getId() + " 的匹配项 divisor 必须为非零数值";
		}
		if (matcher.getCap() != null && !isFiniteNonNegative(matcher.getCap())) {
			return "规则 " + rule.getId() + " 的匹配项 cap 必须为非负数值";
		}
		if (matcher.getMultiplier() != null && !isFiniteNonNegative(matcher.getMultiplier())) {
			return "规则 " + rule.getId() + " 的匹配项 multiplier 必须为非负数值";
		}
		if (matcher.getTiers() != null) {
			for (StatTier tier : matcher.getTiers()) {
				if (tier == null) {
					return "规则 " + rule.getId() + " 的匹配项存在空档位";
				}
				if (!isFiniteNonNegative(tier.getThreshold()) || !isFiniteNonNegative(tier.getValue())) {
					return "规则 " + rule.getId() + " 的匹配项档位 threshold/value 必须为非负数值";
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
			Scorely.LOGGER.warn("Scorely 备份损坏配置失败: {}", e.toString());
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
			Scorely.LOGGER.info("Scorely 玩家名称缓存加载：{} 条", playerNames.size());
		} catch (Exception e) {
			Scorely.LOGGER.warn("Scorely 玩家名称缓存加载失败（将重新累积）: {}", e.toString());
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
