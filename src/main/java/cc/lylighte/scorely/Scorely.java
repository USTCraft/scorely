package cc.lylighte.scorely;

import cc.lylighte.scorely.command.ScorelyCommands;
import cc.lylighte.scorely.event.PlayerEvents;
import cc.lylighte.scorely.event.RefreshScheduler;
import cc.lylighte.scorely.event.ServerEvents;
import cc.lylighte.scorely.scoring.DefaultRules;
import cc.lylighte.scorely.scoring.ScoringEngine;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Scorely implements ModInitializer {
	public static final String MOD_ID = "scorely";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Scorely initializing...");
		// 引擎：内置默认规则（占位，Phase 8 由 config.json 替换）
		ScoringEngine engine = new ScoringEngine(DefaultRules.create());
		ScorelyCommands.setEngine(engine);
		// Phase 7：事件层（定时刷新循环 + 合并触发）
		RefreshScheduler scheduler = new RefreshScheduler(engine);
		ScorelyCommands.setScheduler(scheduler);
		ServerEvents.register(scheduler);
		PlayerEvents.register(scheduler);
		// Phase 6：命令系统
		CommandRegistrationCallback.EVENT.register(ScorelyCommands::register);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
