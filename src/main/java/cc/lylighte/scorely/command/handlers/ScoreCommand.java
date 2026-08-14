package cc.lylighte.scorely.command.handlers;

import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import cc.lylighte.scorely.command.ScorelyCommands;
import cc.lylighte.scorely.scoring.ScoringEngine;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.util.ChatHelper;
import cc.lylighte.scorely.util.Lang;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /scorely score} —— 查看自己的积分。
 *
 * <ul>
 *   <li>{@code /scorely score} —— 各规则积分 + 总分；</li>
 *   <li>{@code /scorely score <rule>} —— 指定规则下的积分。</li>
 * </ul>
 */
public final class ScoreCommand {

	private ScoreCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build(ScoringEngine engine) {
		return Commands.literal("score")
			.executes(ctx -> showAll(ctx.getSource(), engine))
			.then(Commands.argument("rule", StringArgumentType.word())
				.executes(ctx -> showRule(ctx.getSource(), engine, StringArgumentType.getString(ctx, "rule"))));
	}

	/** 全部规则积分 + 总分。 */
	private static int showAll(CommandSourceStack source, ScoringEngine engine) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		UUID uuid = player.getUUID();
		String lang = ScorelyCommands.langOf(source);

		StringBuilder sb = new StringBuilder();
		sb.append(ChatHelper.prefix()).append('\n');
		sb.append(ChatHelper.separator()).append('\n');
		for (ScoringRule rule : engine.getRules()) {
			String name = Lang.ruleName(lang, rule);
			double score = engine.getPlayerScore(uuid, rule.getId());
			sb.append("  ").append(ChatHelper.YELLOW).append(name).append(ChatHelper.RESET)
				.append(": ").append(ChatHelper.formatNumber(score)).append('\n');
		}
		sb.append(ChatHelper.separator()).append('\n');
		sb.append("  ").append(ChatHelper.GREEN).append(Lang.format(lang, "cmd.score.total")).append(ChatHelper.RESET)
			.append(": ").append(ChatHelper.formatNumber(engine.getPlayerTotalScore(uuid)));
		source.sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	/** 指定规则下的积分。 */
	private static int showRule(CommandSourceStack source, ScoringEngine engine, String ruleId) throws CommandSyntaxException {
		ScoringRule rule = ScorelyCommands.findRule(engine, ruleId);
		if (rule == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" "
				+ Lang.format(ScorelyCommands.langOf(source), "cmd.rule.not_found", ruleId))));
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		UUID uuid = player.getUUID();
		String lang = ScorelyCommands.langOf(source);
		String name = Lang.ruleName(lang, rule);
		double score = engine.getPlayerScore(uuid, rule.getId());
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(" "
			+ ChatHelper.YELLOW + name + ChatHelper.RESET + " "
			+ Lang.format(lang, "cmd.score.rule", ChatHelper.formatNumber(score)))), false);
		return 1;
	}
}
