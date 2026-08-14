package cc.lylighte.scorely.util;

import java.util.Locale;

/**
 * 聊天消息文本工具。
 *
 * <p>提供聊天展示常用的文本片段（前缀、分隔线、颜色码、数字格式化）。
 * 纯字符串实现，不依赖 Minecraft 类型——命令层将结果通过
 * {@code net.minecraft.network.chat.Component} 包装后发送给玩家。</p>
 *
 * <p>颜色码使用 Minecraft 传统 {@code §} 标记（服务端文本组件渲染时同样识别）。</p>
 */
public final class ChatHelper {

	// ========== 颜色码常量 ==========

	public static final String BLACK = "§0";
	public static final String DARK_BLUE = "§1";
	public static final String DARK_GREEN = "§2";
	public static final String DARK_AQUA = "§3";
	public static final String DARK_RED = "§4";
	public static final String DARK_PURPLE = "§5";
	public static final String GOLD = "§6";
	public static final String GRAY = "§7";
	public static final String DARK_GRAY = "§8";
	public static final String BLUE = "§9";
	public static final String GREEN = "§a";
	public static final String AQUA = "§b";
	public static final String RED = "§c";
	public static final String LIGHT_PURPLE = "§d";
	public static final String YELLOW = "§e";
	public static final String WHITE = "§f";
	public static final String RESET = "§r";

	// ========== 常用文本片段 ==========

	/** 模组前缀 {@code [Scorely]}，绿色。 */
	public static String prefix() {
		return GREEN + "[Scorely]" + RESET;
	}

	/** 带前缀的消息，如 {@code [Scorely] 总分: 1,234}。 */
	public static String prefix(String message) {
		return prefix() + " " + message;
	}

	/** 无标题分隔线。 */
	public static String separator() {
		return GRAY + "----------------------------------------" + RESET;
	}

	/** 带标题的分隔线，如 {@code ----- 挖掘榜 -----}。 */
	public static String separator(String title) {
		if (title == null || title.isEmpty()) {
			return separator();
		}
		return GRAY + "------ " + YELLOW + title + GRAY + " ------";
	}

	// ========== 数字格式化 ==========

	/**
	 * 积分数字格式化：千分位分组，固定 {@code Locale.ROOT}（防服务器 locale 影响分隔符）。
	 *
	 * <ul>
	 *   <li>整数 → {@code 1,234}</li>
	 *   <li>小数 → 保留两位，如 {@code 1,234.50}</li>
	 * </ul>
	 */
	public static String formatNumber(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "0";
		}
		if (value == Math.floor(value)) {
			return String.format(Locale.ROOT, "%,.0f", value);
		}
		return String.format(Locale.ROOT, "%,.2f", value);
	}

	private ChatHelper() {
	}
}
