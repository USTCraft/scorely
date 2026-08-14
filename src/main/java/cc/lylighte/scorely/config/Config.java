package cc.lylighte.scorely.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 通用 JSON 配置文件读写工具。
 *
 * <p>基于 Minecraft 内置的 Gson，提供类型安全的加载与保存：
 * <ul>
 *   <li>{@link #load(Path, Class)} / {@link #load(Path, Type)} 读取 JSON 文件并反序列化</li>
 *   <li>{@link #save(Path, Object)} 将对象序列化为格式化 JSON 并写入磁盘（自动创建父目录）</li>
 *   <li>{@link #saveAtomic(Path, Object)} 原子写（tmp + move），崩溃不产生半写文件</li>
 *   <li>{@link #exists(Path)} 判断配置文件是否存在</li>
 * </ul>
 * </p>
 *
 * <p>仅依赖标准 Java + Gson，不依赖 Minecraft 类型。</p>
 */
public final class Config {

	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();

	private Config() {
	}

	/** 配置文件是否存在。 */
	public static boolean exists(Path path) {
		return Files.isRegularFile(path);
	}

	/**
	 * 读取 JSON 配置文件。
	 *
	 * @param path 配置文件路径
	 * @param type 目标类型
	 * @return 反序列化后的对象
	 * @throws IOException        文件不存在或读取失败
	 * @throws JsonSyntaxException 文件内容不是合法 JSON 或与目标类型不匹配
	 */
	public static <T> T load(Path path, Class<T> type) throws IOException {
		return load(path, (Type) type);
	}

	/**
	 * 读取 JSON 配置文件（泛型容器用 {@link Type}）。
	 *
	 * @param path 配置文件路径
	 * @param type 目标类型（如 {@code new TypeToken<Map<String, String>>(){}.getType()}）
	 * @return 反序列化后的对象
	 * @throws IOException        文件不存在或读取失败
	 * @throws JsonSyntaxException 文件内容不是合法 JSON 或与目标类型不匹配
	 */
	public static <T> T load(Path path, Type type) throws IOException {
		if (!exists(path)) {
			throw new IOException("Config file not found: " + path);
		}
		String json = Files.readString(path, StandardCharsets.UTF_8);
		return GSON.fromJson(json, type);
	}

	/**
	 * 写入 JSON 配置文件（覆盖已有内容，自动创建父目录）。
	 *
	 * @param path 配置文件路径
	 * @param data 待序列化的对象
	 * @throws IOException 写入失败
	 */
	public static void save(Path path, Object data) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		String json = GSON.toJson(data);
		Files.writeString(path, json, StandardCharsets.UTF_8);
	}

	/**
	 * 原子写入 JSON 配置文件（tmp + move）。
	 *
	 * <p>先写同目录临时文件，再原子移动到目标路径——服务器崩溃时磁盘上要么是旧文件、
	 * 要么是完整新文件，不会出现半写的损坏状态。文件系统不支持原子移动时自动回退普通移动。</p>
	 *
	 * @param path 配置文件路径
	 * @param data 待序列化的对象
	 * @throws IOException 写入失败
	 */
	public static void saveAtomic(Path path, Object data) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		String json = GSON.toJson(data);
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
