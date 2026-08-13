package cc.lylighte.scorely.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.StatsCounter;

import java.util.HashMap;
import java.util.Map;

/**
 * 版本差异封装层（唯一允许直接引用 Minecraft 内部类的入口之一）。
 *
 * <p>当前职责：从在线玩家内存中的 {@code StatsCounter} 实时读取全部统计，
 * 输出与 {@link cc.lylighte.scorely.stats.StatsReader} 磁盘解析完全一致的键值格式
 * {@code "统计类型/统计项" → 累计值}。</p>
 *
 * <p>26.2 Mojang mapping：{@code ServerPlayer#getStats()} → {@code StatsCounter}；
 * 统计类型注册表 {@code BuiltInRegistries.STAT_TYPE}。</p>
 */
public final class CompatHelper {

	private CompatHelper() {
	}

	/**
	 * 读取在线玩家的全部统计值（仅包含累计值大于 0 的项）。
	 *
	 * <p>实现：遍历注册表中的全部统计类型及其注册统计项，逐项取 {@code StatsCounter} 中的值。
	 * 注册表遍历约 5000 项，O(1) 取值，仅用于低频全量刷新，开销可忽略。</p>
	 *
	 * @param player 在线玩家
	 * @return 统计键值表，如 {@code "minecraft:mined/minecraft:stone" → 123}
	 */
	public static Map<String, Integer> readPlayerStats(ServerPlayer player) {
		Map<String, Integer> result = new HashMap<>();
		StatsCounter counter = player.getStats();
		// 泛型通配符无法在 StatType<?> 上安全调用 getRegistry().getKey(...)，collectType 内部使用 raw type
		for (StatType<?> type : BuiltInRegistries.STAT_TYPE) {
			collectType(type, counter, result);
		}
		return result;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void collectType(StatType<?> type, StatsCounter counter, Map<String, Integer> result) {
		Identifier typeId = BuiltInRegistries.STAT_TYPE.getKey(type);
		if (typeId == null) {
			return;
		}
		String typeKey = typeId.toString();
		net.minecraft.core.Registry registry = type.getRegistry();
		// StatType<?> 实现 Iterable<Stat<?>>，直接遍历 Stat<?> 即可，无需 raw 强转
		for (Stat<?> stat : type) {
			int value = counter.getValue(stat);
			if (value <= 0) {
				continue;
			}
			Identifier valueId = registry.getKey(stat.getValue());
			if (valueId == null) {
				continue;
			}
			result.put(typeKey + "/" + valueId.toString(), value);
		}
	}
}
