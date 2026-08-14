package cc.lylighte.scorely.command;

import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import cc.lylighte.scorely.command.handlers.AdminCommand;
import cc.lylighte.scorely.command.handlers.RankCommand;
import cc.lylighte.scorely.command.handlers.RefreshCommand;
import cc.lylighte.scorely.command.handlers.ScoreCommand;
import cc.lylighte.scorely.config.ConfigManager;
import cc.lylighte.scorely.event.RefreshScheduler;
import cc.lylighte.scorely.scoring.ScoringEngine;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.util.ChatHelper;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Scorely 命令注册入口（{@code /scorely} 命令树）。
 *
 * <p>命令树结构：</p>
 * <ul>
 *   <li>{@code /scorely} —— 帮助信息</li>
 *   <li>{@code /scorely score [rule]} —— 查看自己的积分（可指定规则）</li>
 *   <li>{@code /scorely refresh} —— 刷新自己的积分（单玩家重算）</li>
 *   <li>{@code /scorely rank [rule] [page]} —— 排行榜（总榜 / 指定规则，可翻页）</li>
 *   <li>{@code /scorely admin reload|refresh|rule list} —— 管理（OP）</li>
 * </ul>
 *
 * <p>引擎与刷新调度器引用通过 {@link #setEngine} / {@link #setScheduler} 注入，
 * 由入口统一构建。本类同时提供命令层共享工具（玩家名解析、规则查找）。</p>
 */
public final class ScorelyCommands {

	/** 默认空引擎（事件层接入前无数据源，命令框架先行可用）。 */
	private static ScoringEngine engine = new ScoringEngine(List.of());

	/** 刷新调度器（Phase 7 事件层提供；可能为 null，admin refresh 需先注入）。 */
	private static RefreshScheduler scheduler;

	/** 配置管理（Phase 8.2：热重载 + 名称缓存展示；可能为 null）。 */
	private static ConfigManager configManager;

	private ScorelyCommands() {
	}

	/** 注入积分引擎（入口初始化时调用）。 */
	public static void setEngine(ScoringEngine newEngine) {
		engine = newEngine != null ? newEngine : new ScoringEngine(List.of());
	}

	/** 注入刷新调度器（入口初始化时调用）。 */
	public static void setScheduler(RefreshScheduler newScheduler) {
		scheduler = newScheduler;
	}

	/** 注入配置管理（入口初始化时调用）。 */
	public static void setConfigManager(ConfigManager newConfigManager) {
		configManager = newConfigManager;
	}

	/**
	 * 注册 {@code /scorely} 命令树（Fabric CommandRegistrationCallback 回调）。
	 *
	 * @param dispatcher     命令分发器
	 * @param registryAccess 命令构建上下文
	 * @param environment    注册环境（集成/专用服务器）
	 */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
		dispatcher.register(Commands.literal("scorely")
			.executes(ScorelyCommands::help)
			.then(ScoreCommand.build(engine))
			.then(RefreshCommand.build(scheduler))
			.then(RankCommand.build(engine))
			.then(AdminCommand.build(engine, scheduler, configManager)));
	}

	/** {@code /scorely} —— 帮助信息。 */
	private static int help(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		StringBuilder sb = new StringBuilder();
		sb.append(ChatHelper.prefix()).append('\n');
		sb.append(ChatHelper.separator("命令帮助")).append('\n');
		sb.append("  /scorely score [rule]        查看自己的积分\n");
		sb.append("  /scorely refresh             刷新自己的积分\n");
		sb.append("  /scorely rank [rule] [page]  排行榜（总榜/指定规则，可翻页）\n");
		sb.append("  /scorely admin reload        重载配置 (OP)\n");
		sb.append("  /scorely admin refresh       强制刷新积分 (OP)\n");
		sb.append("  /scorely admin rule list     查看积分规则 (OP)");
		source.sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	/**
	 * 解析玩家显示名：在线玩家取真实名字，离线玩家查名称缓存，
	 * 缓存未记录时显示 UUID 短格式（前 8 位）。
	 *
	 * @param server 服务器实例（可能为 null）
	 * @param uuid   玩家 UUID
	 * @return 显示名
	 */
	public static String playerName(MinecraftServer server, UUID uuid) {
		if (server != null) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				return player.getName().getString();
			}
		}
		if (configManager != null) {
			String cached = configManager.getPlayerName(uuid);
			if (cached != null) {
				return cached;
			}
		}
		return uuid.toString().substring(0, 8);
	}

	/** 按规则 ID 查找规则（不存在返回 null）。 */
	public static ScoringRule findRule(ScoringEngine targetEngine, String ruleId) {
		if (targetEngine == null || ruleId == null) {
			return null;
		}
		for (ScoringRule rule : targetEngine.getRules()) {
			if (ruleId.equals(rule.getId())) {
				return rule;
			}
		}
		return null;
	}
}
