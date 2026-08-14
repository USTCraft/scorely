package cc.lylighte.scorely.command.handlers;

import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import cc.lylighte.scorely.config.ConfigManager;
import cc.lylighte.scorely.event.RefreshScheduler;
import cc.lylighte.scorely.scoring.ScoringEngine;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.util.ChatHelper;
import cc.lylighte.scorely.util.Result;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /scorely admin} —— 管理命令（需 OP 权限）。
 *
 * <ul>
 *   <li>{@code /scorely admin reload} —— 热重载配置（校验通过才生效，失败保留旧配置）；</li>
 *   <li>{@code /scorely admin refresh} —— 强制全量刷新积分（受周期配额限制）；</li>
 *   <li>{@code /scorely admin rule list} —— 列出所有积分规则及其计分配置。</li>
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
					.executes(ctx -> ruleList(ctx.getSource(), engine))));
	}

	/**
	 * 热重载配置：校验通过才替换引擎并立即重算；失败保留旧配置并提示原因。
	 */
	private static int reload(CommandSourceStack source, ConfigManager configManager, ScoringEngine engine, RefreshScheduler scheduler) {
		if (configManager == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" 配置服务未就绪")));
			return 0;
		}
		Result result = configManager.reload();
		if (!result.isSuccess()) {
			source.sendFailure(Component.literal(ChatHelper.prefix(
				" " + ChatHelper.RED + result.getMessage() + ChatHelper.RESET
					+ "（当前配置保持生效）")));
			return 0;
		}
		engine.setRules(configManager.getRules());
		scheduler.setRefreshIntervalMinutes(configManager.getRefreshIntervalMinutes());
		Result refresh = scheduler.refreshNow();
		if (!refresh.isSuccess()) {
			source.sendFailure(Component.literal(ChatHelper.prefix(
				" " + result.getMessage() + "，但" + refresh.getMessage() + "（将随下次定时刷新生效）")));
			return 1;
		}
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(
			" " + result.getMessage() + "，已应用并立即重算")), false);
		return 1;
	}

	/** 强制全量刷新（受周期配额限制，配额用尽时拒绝）。 */
	private static int refresh(CommandSourceStack source, RefreshScheduler scheduler) {
		Result result = scheduler.refreshNow();
		String message = result.isSuccess()
			? " " + result.getMessage()
			: " " + ChatHelper.RED + result.getMessage() + ChatHelper.RESET;
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(message)), false);
		return result.isSuccess() ? 1 : 0;
	}

	/** 列出所有积分规则（含计分配置）。 */
	private static int ruleList(CommandSourceStack source, ScoringEngine engine) {
		List<ScoringRule> rules = engine.getRules();
		StringBuilder sb = new StringBuilder();
		sb.append(ChatHelper.prefix()).append('\n');
		sb.append(ChatHelper.separator("积分规则 (" + rules.size() + ")")).append('\n');
		if (rules.isEmpty()) {
			sb.append("  ").append(ChatHelper.GRAY).append("暂无规则").append(ChatHelper.RESET);
		} else {
			for (ScoringRule rule : rules) {
				sb.append(describe(rule)).append('\n');
			}
			sb.setLength(sb.length() - 1); // 去掉末尾换行
		}
		source.sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	/** 单条规则摘要：名称、类型、计分模式与关键配置。 */
	private static String describe(ScoringRule rule) {
		String name = rule.getDisplayName() != null ? rule.getDisplayName() : rule.getId();
		StringBuilder sb = new StringBuilder();
		sb.append("  ").append(ChatHelper.YELLOW).append(name).append(ChatHelper.RESET)
			.append(" [").append(rule.getType()).append(']');

		if (rule.isStatType()) {
			// Phase 5.1：展示计分模式（线性/阶段）与关键配置
			if (rule.getTiers() != null && !rule.getTiers().isEmpty()) {
				sb.append(" ").append(ChatHelper.AQUA).append("阶段 ").append(rule.getTiers().size()).append(" 档").append(ChatHelper.RESET);
			} else {
				sb.append(" ").append(ChatHelper.AQUA).append("线性 ×").append(ChatHelper.formatNumber(rule.getMultiplier())).append(ChatHelper.RESET);
			}
			sb.append(" 封顶 ").append(ChatHelper.formatNumber(rule.getCap()))
				.append(" 换算 ÷").append(ChatHelper.formatNumber(rule.getDivisor()))
				.append(" 匹配 ").append(rule.getMatchers().size()).append(" 项");
		} else if (rule.isAdvancementType()) {
			sb.append(" 默认 ").append(ChatHelper.formatNumber(rule.getDefaultValue()))
				.append(" 分 特殊进度 ").append(rule.getAdvancementValues().size()).append(" 个");
		}

		if (!rule.isEnabled()) {
			sb.append(" ").append(ChatHelper.RED).append("已禁用").append(ChatHelper.RESET);
		}
		return sb.toString();
	}
}
