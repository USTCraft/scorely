package cc.lylighte.scorely.event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import cc.lylighte.scorely.advancement.AdvancementReader;
import cc.lylighte.scorely.stats.StatsReader;

/**
 * 离线玩家统计数据缓存（减少磁盘访问）。
 *
 * <p><strong>核心洞察</strong>：服务器运行期间，离线玩家的 {@code stats/<uuid>.json} 与
 * {@code advancements/<uuid>.json} 不会变化（仅在登录/登出/自动保存时写盘）。
 * 因此缓存解析结果，每次刷新只对文件做 {@code stat} 比对 <strong>mtime + size 指纹</strong>，
 * 未变化则直接复用，避免反复读取并解析 JSON。</p>
 *
 * <p>失效场景（OP 手动修改文件等）：指纹变化后自动重读重解析，安全自愈。</p>
 *
 * <p>同时维护已知玩家 UUID 集合（启动全扫 + 玩家加入事件补充），供调度器判断离线玩家范围，
 * 避免每次刷新都扫描目录。</p>
 *
 * <p><strong>线程约束</strong>：非线程安全，仅供服务器线程（tick 线程）调用。</p>
 */
public final class PlayerDataCache {

	/** 文件指纹（mtime + size）。 */
	private record Fingerprint(long lastModified, long size) {
	}

	/** 单玩家缓存条目。 */
	private static final class Entry {
		Map<String, Integer> stats = Map.of();
		Set<String> advancements = Set.of();
		Fingerprint statsFp;
		Fingerprint advancementsFp;
	}

	private final Map<UUID, Entry> entries = new HashMap<>();
	private final Set<UUID> knownPlayers = new HashSet<>();

	/**
	 * 已记录的全部玩家 UUID（在线 + 离线），不可变视图。
	 *
	 * @return 玩家 UUID 集合
	 */
	public Set<UUID> getKnownPlayers() {
		return Collections.unmodifiableSet(knownPlayers);
	}

	/** 记录一个已知玩家（玩家加入事件时调用；已存在则忽略）。 */
	public void addKnownPlayer(UUID uuid) {
		if (uuid != null) {
			knownPlayers.add(uuid);
		}
	}

	/** 批量记录已知玩家（启动全扫目录时调用）。 */
	public void addKnownPlayers(Collection<UUID> uuids) {
		if (uuids == null) {
			return;
		}
		for (UUID uuid : uuids) {
			addKnownPlayer(uuid);
		}
	}

	/**
	 * 刷新一个离线玩家的数据：文件指纹未变化则复用缓存，变化则重新读盘解析。
	 *
	 * <p>文件不存在视为空数据（指纹为 {@code null} 同样缓存，避免反复探测）。</p>
	 *
	 * @param uuid             玩家 UUID
	 * @param statsDir         {@code world/stats} 目录
	 * @param advancementsDir  {@code world/advancements} 目录
	 * @throws IOException 文件存在但无法读取
	 */
	public void refresh(UUID uuid, Path statsDir, Path advancementsDir) throws IOException {
		Entry entry = entries.computeIfAbsent(uuid, key -> new Entry());
		entry.stats = refreshStats(entry, statsDir, uuid);
		entry.advancements = refreshAdvancements(entry, advancementsDir, uuid);
	}

	/**
	 * 获取离线玩家缓存的统计键值表（需先 {@link #refresh}）。
	 *
	 * @param uuid 玩家 UUID
	 * @return 统计键值表（无缓存时为空表）
	 */
	public Map<String, Integer> getStats(UUID uuid) {
		Entry entry = entries.get(uuid);
		return entry == null ? Map.of() : entry.stats;
	}

	/**
	 * 获取离线玩家缓存的已完成进度集合（需先 {@link #refresh}）。
	 *
	 * @param uuid 玩家 UUID
	 * @return 已完成进度集合（无缓存时为空集合）
	 */
	public Set<String> getAdvancements(UUID uuid) {
		Entry entry = entries.get(uuid);
		return entry == null ? Set.of() : entry.advancements;
	}

	private Map<String, Integer> refreshStats(Entry entry, Path statsDir, UUID uuid) throws IOException {
		Path file = statsDir.resolve(uuid + ".json");
		Fingerprint fp = fingerprint(file);
		if (Objects.equals(fp, entry.statsFp)) {
			return entry.stats;
		}
		entry.statsFp = fp;
		return StatsReader.readStats(file);
	}

	private Set<String> refreshAdvancements(Entry entry, Path advancementsDir, UUID uuid) throws IOException {
		Path file = advancementsDir.resolve(uuid + ".json");
		Fingerprint fp = fingerprint(file);
		if (Objects.equals(fp, entry.advancementsFp)) {
			return entry.advancements;
		}
		entry.advancementsFp = fp;
		return AdvancementReader.readCompletedAdvancements(file);
	}

	/** 计算文件指纹；文件不存在返回 {@code null}。 */
	private static Fingerprint fingerprint(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		return new Fingerprint(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
	}
}
