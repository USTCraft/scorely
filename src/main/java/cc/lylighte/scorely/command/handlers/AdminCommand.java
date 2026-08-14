package cc.lylighte.scorely.command.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import cc.lylighte.scorely.command.ScorelyCommands;
import cc.lylighte.scorely.config.ConfigManager;
import cc.lylighte.scorely.event.RefreshScheduler;
import cc.lylighte.scorely.scoring.ScoringEngine;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.util.ChatHelper;
import cc.lylighte.scorely.util.Lang;
import cc.lylighte.scorely.util.Result;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /scorely admin} —— 管理命令（需 OP 权限）。
 *
 * <ul>
 *   <li>{@code /scorely admin reload} —— 热重载配置（校验通过才生效，失败保留旧配置）；</li>
 *   <li>{@code /scorely admin refresh} —— 强制全量刷新积分（受周期配额限制）；</li>
 *   <li>{@code /scorely admin rule list} —— 列出所有积分规则及其计分配置；</li>
 *   <li>{@code /scorely admin star add|remove|list} —— 管理打星名单（Phase 11）。</li>
 * </ul>
 */
public final class AdminCommand {

	private AdminCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build(ScoringEngine engine, RefreshScheduler scheduler, ConfigManager configManager) {
		return Commands.literal("admin")
			.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
			.then(Commands.literal("reload")
				.executes(ctx -> reload(ctx.getSource(), configManager, engine, scheduler)))
			.then(Commands.literal("refresh")
				.executes(ctx -> refresh(ctx.getSource(), scheduler)))
			.then(Commands.literal("rule")
				.then(Commands.literal("list")
					.executes(ctx -> ruleList(ctx.getSource(), engine))))
			.then(Commands.literal("star")
				.then(Commands.literal("add")
					.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> starAdd(ctx.getSource(), configManager, StringArgumentType.getString(ctx, "player")))))
				.then(Commands.literal("remove")
					.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> starRemove(ctx.getSource(), configManager, StringArgumentType.getString(ctx, "player")))))
				.then(Commands.literal("list")
					.executes(ctx -> starList(ctx.getSource(), configManager))));
	}

	/**
	 * 热重载配置：校验通过才替换引擎并立即重算；失败保留旧配置并提示原因。
	 */
	private static int reload(CommandSourceStack source, ConfigManager configManager, ScoringEngine engine, RefreshScheduler scheduler) {
		String lang = ScorelyCommands.langOf(source);
		if (configManager == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.config_unavailable"))));
			return 0;
		}
		Result result = configManager.reload();
		if (!result.isSuccess()) {
			// 失败：透传校验错误（key 已具体），并注明旧配置保持生效
			String message = Lang.format(lang, "cmd.admin.reload.kept",
				Lang.format(lang, result.getKey(), result.getArgs()));
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + ChatHelper.RED + message + ChatHelper.RESET)));
			return 0;
		}
		engine.setRules(configManager.getRules());
		scheduler.setRefreshIntervalMinutes(configManager.getRefreshIntervalMinutes());
		Result refresh = scheduler.refreshNow();
		if (!refresh.isSuccess()) {
			// 配置已生效但全量刷新失败（如服务器未就绪）：随下次定时刷新生效
			String message = Lang.format(lang, "cmd.admin.reload.failed_refresh",
				Lang.format(lang, result.getKey(), result.getArgs()),
				Lang.format(lang, refresh.getKey(), refresh.getArgs()));
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + ChatHelper.RED + message + ChatHelper.RESET)));
			return 1;
		}
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.reload.applied",
			Lang.format(lang, result.getKey(), result.getArgs())))), false);
		return 1;
	}

	/** 强制全量刷新。 */
	private static int refresh(CommandSourceStack source, RefreshScheduler scheduler) {
		String lang = ScorelyCommands.langOf(source);
		Result result = scheduler.refreshNow();
		String message = Lang.format(lang, result.getKey(), result.getArgs());
		String colored = result.isSuccess()
			? " " + message
			: " " + ChatHelper.RED + message + ChatHelper.RESET;
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(colored)), false);
		return result.isSuccess() ? 1 : 0;
	}

	/** 列出所有积分规则（含计分配置）。 */
	private static int ruleList(CommandSourceStack source, ScoringEngine engine) {
		String lang = ScorelyCommands.langOf(source);
		List<ScoringRule> rules = engine.getRules();
		StringBuilder sb = new StringBuilder();
		sb.append(ChatHelper.prefix()).append('\n');
		sb.append(ChatHelper.separator(Lang.format(lang, "cmd.admin.rule.title", rules.size()))).append('\n');
		if (rules.isEmpty()) {
			sb.append("  ").append(ChatHelper.GRAY).append(Lang.format(lang, "cmd.admin.rule.empty")).append(ChatHelper.RESET);
		} else {
			for (ScoringRule rule : rules) {
				sb.append(describe(lang, rule)).append('\n');
			}
			sb.setLength(sb.length() - 1); // 去掉末尾换行
		}
		source.sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	/** 单条规则摘要：名称、类型、计分模式与关键配置。 */
	private static String describe(String lang, ScoringRule rule) {
		String name = Lang.ruleName(lang, rule);
		StringBuilder sb = new StringBuilder();
		sb.append("  ").append(ChatHelper.YELLOW).append(name).append(ChatHelper.RESET)
			.append(" [").append(rule.getType()).append(']');

		if (rule.isStatType()) {
			// Phase 5.1：展示计分模式（线性/阶段）与关键配置
			if (rule.getTiers() != null && !rule.getTiers().isEmpty()) {
				sb.append(" ").append(ChatHelper.AQUA)
					.append(Lang.format(lang, "cmd.admin.rule.tiered", rule.getTiers().size()))
					.append(ChatHelper.RESET);
			} else {
				sb.append(" ").append(ChatHelper.AQUA)
					.append(Lang.format(lang, "cmd.admin.rule.linear", ChatHelper.formatNumber(rule.getMultiplier())))
					.append(ChatHelper.RESET);
			}
			sb.append(" ")
				.append(Lang.format(lang, "cmd.admin.rule.cap", ChatHelper.formatNumber(rule.getCap())))
				.append(" ")
				.append(Lang.format(lang, "cmd.admin.rule.divisor", ChatHelper.formatNumber(rule.getDivisor())))
				.append(" ")
				.append(Lang.format(lang, "cmd.admin.rule.matchers", rule.getMatchers().size()));
		} else if (rule.isAdvancementType()) {
			sb.append(" ")
				.append(Lang.format(lang, "cmd.admin.rule.default_value", ChatHelper.formatNumber(rule.getDefaultValue())))
				.append(" ")
				.append(Lang.format(lang, "cmd.admin.rule.special", rule.getAdvancementValues().size()));
			// Phase 12：帧分层展示（task/goal/challenge）
			if (!rule.getFrameValues().isEmpty()) {
				sb.append(" ").append(ChatHelper.AQUA)
					.append(Lang.format(lang, "cmd.admin.rule.frames", rule.getFrameValues().size()))
					.append(ChatHelper.RESET);
			}
		}

		// Phase 12：maxScore 展示（正值=满分，负值=封底）
		if (rule.getMaxScore() > 0) {
			sb.append(" ").append(ChatHelper.GOLD)
				.append(Lang.format(lang, "cmd.admin.rule.max_score", ChatHelper.formatNumber(rule.getMaxScore())))
				.append(ChatHelper.RESET);
		} else if (rule.getMaxScore() < 0) {
			sb.append(" ").append(ChatHelper.GOLD)
				.append(Lang.format(lang, "cmd.admin.rule.max_score_floor", ChatHelper.formatNumber(rule.getMaxScore())))
				.append(ChatHelper.RESET);
		}
		// Phase 12：非默认权重展示（默认 1 不显示）
		if (rule.getWeight() > 0 && rule.getWeight() != 1.0) {
			sb.append(" ").append(ChatHelper.GRAY)
				.append(Lang.format(lang, "cmd.admin.rule.weight", ChatHelper.formatNumber(rule.getWeight())))
				.append(ChatHelper.RESET);
		}

		if (!rule.isEnabled()) {
			sb.append(" ").append(ChatHelper.RED).append(Lang.format(lang, "cmd.admin.rule.disabled")).append(ChatHelper.RESET);
		}
		return sb.toString();
	}

	// ========== 打星名单（Phase 11） ==========

	/** 将玩家加入打星名单（在线优先，离线回退名称缓存反查）。 */
	private static int starAdd(CommandSourceStack source, ConfigManager configManager, String playerName) {
		String lang = ScorelyCommands.langOf(source);
		if (configManager == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.config_unavailable"))));
			return 0;
		}
		UUID target = resolvePlayerUuid(source, configManager, playerName);
		if (target == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.star.player_not_found", playerName))));
			return 0;
		}
		Result result = configManager.addStarPlayer(target);
		if (result != null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + ChatHelper.RED
				+ Lang.format(lang, result.getKey(), result.getArgs()) + ChatHelper.RESET)));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.star.added", playerName))), false);
		return 1;
	}

	/** 将玩家移出打星名单。 */
	private static int starRemove(CommandSourceStack source, ConfigManager configManager, String playerName) {
		String lang = ScorelyCommands.langOf(source);
		if (configManager == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.config_unavailable"))));
			return 0;
		}
		UUID target = resolvePlayerUuid(source, configManager, playerName);
		if (target == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.star.player_not_found", playerName))));
			return 0;
		}
		Result result = configManager.removeStarPlayer(target);
		if (result != null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + ChatHelper.RED
				+ Lang.format(lang, result.getKey(), result.getArgs()) + ChatHelper.RESET)));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.star.removed", playerName))), false);
		return 1;
	}

	/**
	 * 列出打星名单（名称 + UUID），分两段：手动名单（config starPlayers）
	 * 与 OP 自动识别（starOps 开启时经 ops.json 判定，排除已在手动名单者）。
	 */
	private static int starList(CommandSourceStack source, ConfigManager configManager) {
		String lang = ScorelyCommands.langOf(source);
		if (configManager == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.admin.config_unavailable"))));
			return 0;
		}
		MinecraftServer server = source.getServer();
		Set<UUID> manual = configManager.getStarPlayers();

		// OP 自动识别（starOps 开启）：枚举 ops.json 条目，排除已在手动名单者
		List<UUID> autoOps = new ArrayList<>();
		if (configManager.isStarOpsEnabled() && server != null) {
			for (ServerOpListEntry entry : server.getPlayerList().getOps().getEntries()) {
				if (entry.getUser() != null && entry.getUser().id() != null
					&& !manual.contains(entry.getUser().id())) {
					autoOps.add(entry.getUser().id());
				}
			}
		}

		int total = manual.size() + autoOps.size();
		StringBuilder sb = new StringBuilder();
		sb.append(ChatHelper.prefix()).append('\n');
		sb.append(ChatHelper.separator(Lang.format(lang, "cmd.admin.star.title", total))).append('\n');
		if (total == 0) {
			sb.append("  ").append(ChatHelper.GRAY).append(Lang.format(lang, "cmd.admin.star.empty")).append(ChatHelper.RESET);
		} else {
			if (!manual.isEmpty()) {
				sb.append("  ").append(ChatHelper.GRAY)
					.append(Lang.format(lang, "cmd.admin.star.manual", manual.size()))
					.append(ChatHelper.RESET).append('\n');
				for (UUID uuid : manual) {
					appendStarEntry(sb, lang, server, uuid);
				}
			}
			if (!autoOps.isEmpty()) {
				sb.append("  ").append(ChatHelper.GRAY)
					.append(Lang.format(lang, "cmd.admin.star.auto", autoOps.size()))
					.append(ChatHelper.RESET).append('\n');
				for (UUID uuid : autoOps) {
					appendStarEntry(sb, lang, server, uuid);
				}
			}
			sb.setLength(sb.length() - 1); // 去掉末尾换行
		}
		source.sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	/** 追加一条打星条目：标记符号 + 名字 + UUID。 */
	private static void appendStarEntry(StringBuilder sb, String lang, MinecraftServer server, UUID uuid) {
		String name = ScorelyCommands.playerName(server, uuid);
		// 打星标记符号可经 lang 覆盖表自定义（Phase 11）
		String starText = Lang.format(lang, "cmd.rank.star");
		if (!starText.isEmpty()) {
			sb.append("  ").append(ChatHelper.YELLOW).append(starText).append(ChatHelper.RESET).append(" ");
		}
		sb.append(ChatHelper.AQUA).append(name).append(ChatHelper.RESET)
			.append("  ").append(ChatHelper.GRAY).append(uuid).append(ChatHelper.RESET).append('\n');
	}

	/** 解析玩家 UUID：在线玩家按真实名字命中，离线回退名称缓存反查。 */
	private static UUID resolvePlayerUuid(CommandSourceStack source, ConfigManager configManager, String playerName) {
		ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(playerName);
		if (online != null) {
			return online.getUUID();
		}
		return configManager.findPlayerUuidByName(playerName);
	}
}
