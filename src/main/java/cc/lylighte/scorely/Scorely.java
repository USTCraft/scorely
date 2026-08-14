package cc.lylighte.scorely;

import cc.lylighte.scorely.command.ScorelyCommands;
import cc.lylighte.scorely.config.ConfigManager;
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
		// Phase 8：配置管理（服务器启动时加载 config.json，替换引擎规则）
		ConfigManager configManager = new ConfigManager();
		// 引擎：先用内置默认规则占位，SERVER_STARTED 时由 config.json 原子替换
		ScoringEngine engine = new ScoringEngine(DefaultRules.create());
		ScorelyCommands.setEngine(engine);
		// Phase 7：事件层（定时刷新循环 + 合并触发）
		RefreshScheduler scheduler = new RefreshScheduler(engine);
		ScorelyCommands.setScheduler(scheduler);
		ScorelyCommands.setConfigManager(configManager);
		ServerEvents.register(scheduler, configManager, engine);
		PlayerEvents.register(scheduler, configManager);
		// Phase 6：命令系统
		CommandRegistrationCallback.EVENT.register(ScorelyCommands::register);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
