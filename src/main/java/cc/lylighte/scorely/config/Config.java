package cc.lylighte.scorely.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 通用 JSON 配置文件读写工具。
 *
 * <p>基于 Minecraft 内置的 Gson，提供类型安全的加载与保存：
 * <ul>
 *   <li>{@link #load(Path, Class)} 读取 JSON 文件并反序列化为目标类型</li>
 *   <li>{@link #save(Path, Object)} 将对象序列化为格式化 JSON 并写入磁盘（自动创建父目录）</li>
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
}
