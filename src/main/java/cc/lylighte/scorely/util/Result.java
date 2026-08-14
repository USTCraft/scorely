package cc.lylighte.scorely.util;

/**
 * 操作结果封装。
 *
 * <p>用于在模块间传递操作执行结果（如命令执行、配置加载、积分刷新）。
 * Phase 9 起消息统一为<strong>翻译键 + 参数</strong>结构：业务层只产 key
 * （如 {@code scheduler.refreshed}），由命令层按玩家语言解析——业务层
 * 不感知语言，展示层统一本地化。</p>
 *
 * <p>纯 Java 实现，不依赖任何 Minecraft 类型。</p>
 */
public final class Result {

	private static final Object[] NO_ARGS = new Object[0];

	private final boolean success;
	private final String key;
	private final Object[] args;

	private Result(boolean success, String key, Object... args) {
		this.success = success;
		this.key = key;
		this.args = args == null ? NO_ARGS : args;
	}

	/** 成功结果（无附加消息）。 */
	public static Result success() {
		return new Result(true, "");
	}

	/** 成功结果（携带翻译键与参数）。 */
	public static Result success(String key, Object... args) {
		return new Result(true, key, args);
	}

	/** 失败结果（携带原因翻译键与参数）。 */
	public static Result failure(String key, Object... args) {
		return new Result(false, key, args);
	}

	/** 是否成功。 */
	public boolean isSuccess() {
		return success;
	}

	/** 是否失败。 */
	public boolean isFailure() {
		return !success;
	}

	/** 翻译键（成功时的说明 / 失败时的原因）。 */
	public String getKey() {
		return key;
	}

	/** 翻译参数（{@code {0}}/{@code {1}} 占位符，可为空数组）。 */
	public Object[] getArgs() {
		return args;
	}

	@Override
	public String toString() {
		return (success ? "SUCCESS" : "FAILURE") + (key.isEmpty() ? "" : ": " + key);
	}
}
