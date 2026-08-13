package cc.lylighte.scorely;

import java.util.List;

import cc.lylighte.scorely.command.ScorelyCommands;
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
		// Phase 6：命令系统（引擎先以空规则初始化，Phase 7 事件层接入后重建）
		ScorelyCommands.setEngine(new ScoringEngine(List.of()));
		CommandRegistrationCallback.EVENT.register(ScorelyCommands::register);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
