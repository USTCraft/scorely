package cc.lylighte.scorely.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家统计数据磁盘读取器。
 *
 * <p>解析 Minecraft 26.2 的 {@code world/stats/<uuid>.json} 文件，输出统一格式的统计键值：
 * {@code "统计类型/统计项" → 累计值}，如 {@code "minecraft:mined/minecraft:stone" → 123}。</p>
 *
 * <p>键格式与 {@link cc.lylighte.scorely.compat.CompatHelper#readPlayerStats} 的内存读取完全一致，
 * 积分引擎无需区分数据来源。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class StatsReader {

	private StatsReader() {
	}

	/**
	 * 读取指定玩家的统计文件（{@code <uuid>.json}）。
	 *
	 * @param statsDir    {@code world/stats} 目录
	 * @param playerUuid  玩家 UUID（与文件名匹配）
	 * @return 统计键值表（文件不存在或内容损坏时返回空表，不抛异常）
	 * @throws IOException 文件无法读取
	 */
	public static Map<String, Integer> readStats(Path statsDir, UUID playerUuid) throws IOException {
		return readStats(statsDir.resolve(playerUuid + ".json"));
	}

	/**
	 * 解析单个统计 JSON 文件。
	 *
	 * <p>宽容策略：非对象/非数字字段自动跳过；文件不存在或损坏返回空表。</p>
	 *
	 * @param statsFile 统计文件路径
	 * @return 统计键值表
	 * @throws IOException 文件存在但无法读取
	 */
	public static Map<String, Integer> readStats(Path statsFile) throws IOException {
		Map<String, Integer> result = new HashMap<>();
		if (!Files.isRegularFile(statsFile)) {
			return result;
		}

		JsonObject root;
		try {
			root = JsonParser.parseString(Files.readString(statsFile, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IllegalStateException | com.google.gson.JsonParseException e) {
			return result;
		}

		JsonObject stats = root.getAsJsonObject("stats");
		if (stats == null) {
			return result;
		}

		for (Map.Entry<String, JsonElement> typeEntry : stats.entrySet()) {
			JsonElement typeValues = typeEntry.getValue();
			if (!typeValues.isJsonObject()) {
				continue;
			}
			for (Map.Entry<String, JsonElement> valueEntry : typeValues.getAsJsonObject().entrySet()) {
				JsonElement count = valueEntry.getValue();
				if (count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
					result.put(typeEntry.getKey() + "/" + valueEntry.getKey(), count.getAsInt());
				}
			}
		}
		return result;
	}
}
