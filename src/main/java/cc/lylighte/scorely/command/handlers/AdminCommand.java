package cc.lylighte.scorely.command.handlers;

import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import cc.lylighte.scorely.scoring.ScoringEngine;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.util.ChatHelper;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /scorely admin} —— 管理命令（需 OP 权限）。
 *
 * <ul>
 *   <li>{@code /scorely admin reload} —— 重载配置（Phase 8 接入 ConfigManager）；</li>
 *   <li>{@code /scorely admin refresh} —— 强制全量刷新积分（Phase 7 接入事件层）；</li>
 *   <li>{@code /scorely admin rule list} —— 列出所有积分规则及其计分配置。</li>
 * </ul>
 */
public final class AdminCommand {

	private AdminCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build(ScoringEngine engine) {
		return Commands.literal("admin")
			.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
			.then(Commands.literal("reload")
				.executes(ctx -> reload(ctx.getSource())))
			.then(Commands.literal("refresh")
				.executes(ctx -> refresh(ctx.getSource())))
			.then(Commands.literal("rule")
				.then(Commands.literal("list")
					.executes(ctx -> ruleList(ctx.getSource(), engine))));
	}

	/** 重载配置（Phase 8 实现）。 */
	private static int reload(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(
			" 配置重载将在 Phase 8 接入（ConfigManager + serverconfig）")), false);
		return 1;
	}

	/** 强制全量刷新（Phase 7 实现）。 */
	private static int refresh(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(
			" 强制刷新将在 Phase 7 接入（事件层 + 定时重算）")), false);
		return 1;
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
