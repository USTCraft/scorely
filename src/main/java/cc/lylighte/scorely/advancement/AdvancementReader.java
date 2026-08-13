package cc.lylighte.scorely.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家进度数据磁盘读取器。
 *
 * <p>解析 Minecraft 26.2 的 {@code world/advancements/<uuid>.json} 文件，输出已完成的进度 ID 集合
 * （如 {@code "minecraft:story/root"}）。</p>
 *
 * <p>磁盘格式（26.2 {@code PlayerAdvancements.Data.CODEC}）：顶层为 {@code 进度ID → 进度状态}，
 * 每个状态含 {@code criteria}（已完成条件及时间）与 {@code done}（布尔，缺失时视为 {@code true}）。</p>
 *
 * <p>输出与 {@link cc.lylighte.scorely.compat.CompatHelper#readCompletedAdvancements} 的内存读取完全一致，
 * 积分引擎无需区分数据来源。</p>
 *
 * <p>纯 Java 实现，不依赖 Minecraft 类型。</p>
 */
public final class AdvancementReader {

	private AdvancementReader() {
	}

	/**
	 * 读取指定玩家的进度文件（{@code <uuid>.json}）。
	 *
	 * @param advancementsDir {@code world/advancements} 目录
	 * @param playerUuid      玩家 UUID（与文件名匹配）
	 * @return 已完成的进度 ID 集合（文件不存在或内容损坏时返回空集合，不抛异常）
	 * @throws IOException 文件无法读取
	 */
	public static Set<String> readCompletedAdvancements(Path advancementsDir, UUID playerUuid) throws IOException {
		return readCompletedAdvancements(advancementsDir.resolve(playerUuid + ".json"));
	}

	/**
	 * 解析单个进度 JSON 文件。
	 *
	 * <p>判定规则：条目状态中 {@code done} 缺失或为 {@code true} 视为已完成；
	 * 状态非对象或 {@code done} 为非法值时跳过该条。</p>
	 *
	 * @param advancementsFile 进度文件路径
	 * @return 已完成的进度 ID 集合
	 * @throws IOException 文件存在但无法读取
	 */
	public static Set<String> readCompletedAdvancements(Path advancementsFile) throws IOException {
		Set<String> result = new HashSet<>();
		if (!Files.isRegularFile(advancementsFile)) {
			return result;
		}

		JsonObject root;
		try {
			root = JsonParser.parseString(Files.readString(advancementsFile, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IllegalStateException | com.google.gson.JsonParseException e) {
			return result;
		}

		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject progress = entry.getValue().getAsJsonObject();
			JsonElement done = progress.get("done");
			if (done == null) {
				// done 缺失：磁盘 CODEC 缺省为 true，视为已完成
				result.add(entry.getKey());
			} else if (done.isJsonPrimitive() && done.getAsJsonPrimitive().isBoolean() && done.getAsBoolean()) {
				result.add(entry.getKey());
			}
		}
		return result;
	}
}
