package cc.lylighte.scorely.command.handlers;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import cc.lylighte.scorely.command.ScorelyCommands;
import cc.lylighte.scorely.event.RefreshScheduler;
import cc.lylighte.scorely.util.ChatHelper;
import cc.lylighte.scorely.util.Lang;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /scorely refresh} —— 刷新自己的积分（任何玩家可执行）。
 *
 * <p>Phase 8.1：走单玩家重算路径（{@link RefreshScheduler#refreshPlayer}），
 * 只计算调用者本人，不消耗全服额外刷新配额。</p>
 */
public final class RefreshCommand {

	private RefreshCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build(RefreshScheduler scheduler) {
		return Commands.literal("refresh")
			.executes(ctx -> refresh(ctx.getSource(), scheduler));
	}

	/** 刷新调用者本人的积分并提示结果。 */
	private static int refresh(CommandSourceStack source, RefreshScheduler scheduler) throws CommandSyntaxException {
		if (scheduler == null) {
			source.sendFailure(Component.literal(ChatHelper.prefix(" " + Lang.format(ScorelyCommands.langOf(source), "cmd.refresh.unavailable"))));
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		scheduler.refreshPlayer(player.getUUID());
		source.sendSuccess(() -> Component.literal(ChatHelper.prefix(
			" " + Lang.format(ScorelyCommands.langOf(source), "cmd.refresh.done"))), false);
		return 1;
	}
}
