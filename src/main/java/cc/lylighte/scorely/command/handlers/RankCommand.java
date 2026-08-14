package cc.lylighte.scorely.command.handlers;

import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import cc.lylighte.scorely.command.ScorelyCommands;
import cc.lylighte.scorely.scoring.ScoreEntry;
import cc.lylighte.scorely.scoring.ScoringEngine;
import cc.lylighte.scorely.scoring.ScoringRule;
import cc.lylighte.scorely.util.ChatHelper;
import cc.lylighte.scorely.util.Lang;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * {@code /scorely rank} —— 排行榜。
 *
 * <ul>
 *   <li>{@code /scorely rank} —— 总榜 Top 10；</li>
 *   <li>{@code /scorely rank <rule>} —— 指定规则排行榜（第 1 页）；</li>
 *   <li>{@code /scorely rank <rule> <page>} —— 翻页查看（每页 10 条）。</li>
 * </ul>
 */
public final class RankCommand {

	/** 每页条数。 */
	private static final int PAGE_SIZE = 10;

	private RankCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build(ScoringEngine engine) {
		return Commands.literal("rank")
			.executes(ctx -> showTotal(ctx.getSource(), engine))
			.then(Commands.argument("rule", StringArgumentType.word())
				.executes(ctx -> showRule(ctx.getSource(), engine, StringArgumentType.getString(ctx, "rule"), 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
					.executes(ctx -> showRule(ctx.getSource(), engine, StringArgumentType.getString(ctx, "rule"),
						IntegerArgumentType.getInteger(ctx, "page")))));
	}

	/** 总榜 Top 10。 */
	private static int showTotal(CommandSourceStack source, ScoringEngine engine) {
		String lang = ScorelyCommands.langOf(source);
		List<ScoreEntry> entries = engine.getTotalLeaderboard(PAGE_SIZE);
		sendRanking(source, lang, Lang.format(lang, "cmd.rank.total"), entries, 1);
		return 1;
	}

	/** 指定规则排行榜（分页）。 */
	private static int showRule(CommandSourceStack source, ScoringEngine engine, String ruleId, int page) {
		String lang = ScorelyCommands.langOf(source);
		ScoringRule rule = ScorelyCommands.findRule(engine, ruleId);
		if (rule == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.rule.not_found", ruleId))));
			return 0;
		}
		List<ScoreEntry> all = engine.getLeaderboard(ruleId, 0);
		String name = Lang.ruleName(lang, rule);

		int start = (page - 1) * PAGE_SIZE;
		if (start >= all.size()) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(lang, "cmd.rank.no_more_page", pageCount(all.size())))));
			return 0;
		}
		int end = Math.min(start + PAGE_SIZE, all.size());
		sendRanking(source, lang, name, all.subList(start, end), page);
		return 1;
	}

	/** 渲染排行榜消息。 */
	private static void sendRanking(CommandSourceStack source, String lang, String title, List<ScoreEntry> entries, int page) {
		MinecraftServer server = source.getServer();
		StringBuilder sb = new StringBuilder();
		sb.append(ChatHelper.prefix()).append('\n');
		sb.append(ChatHelper.separator(Lang.format(lang, "cmd.rank.title_page", title, page))).append('\n');
		if (entries.isEmpty()) {
			sb.append("  ").append(ChatHelper.GRAY).append(Lang.format(lang, "cmd.rank.empty")).append(ChatHelper.RESET);
		} else {
			int rank = (page - 1) * PAGE_SIZE + 1;
			for (ScoreEntry entry : entries) {
				String name = ScorelyCommands.playerName(server, entry.player());
				// Phase 11：打星玩家照常入榜（排名竞争语义下不算正式名次），名字带 ★ 标记
				String star = ScorelyCommands.isStarred(server, entry.player())
					? " " + ChatHelper.YELLOW + "★" + ChatHelper.RESET
					: "";
				sb.append("  ").append(ChatHelper.GOLD).append(rank).append(ChatHelper.RESET)
					.append(". ").append(ChatHelper.AQUA).append(name).append(ChatHelper.RESET)
					.append(star)
					.append("  ").append(ChatHelper.formatNumber(entry.score())).append('\n');
				rank++;
			}
			sb.setLength(sb.length() - 1); // 去掉末尾换行
		}
		source.sendSuccess(() -> Component.literal(sb.toString()), false);
	}

	private static int pageCount(int total) {
		return total == 0 ? 0 : (total + PAGE_SIZE - 1) / PAGE_SIZE;
	}
}
