package cc.lylighte.scorely.util;

/**
 * 操作结果封装。
 *
 * <p>用于在模块间传递操作执行结果（如命令执行、配置加载、积分刷新），
 * 统一以 {@code success + message} 形式表达，避免散落布尔返回值与字符串拼接。</p>
 *
 * <p>纯 Java 实现，不依赖任何 Minecraft 类型。</p>
 */
public final class Result {

	private final boolean success;
	private final String message;

	private Result(boolean success, String message) {
		this.success = success;
		this.message = message;
	}

	/** 成功结果（无附加消息）。 */
	public static Result success() {
		return new Result(true, "");
	}

	/** 成功结果（携带消息）。 */
	public static Result success(String message) {
		return new Result(true, message);
	}

	/** 失败结果（携带原因）。 */
	public static Result failure(String message) {
		return new Result(false, message);
	}

	/** 是否成功。 */
	public boolean isSuccess() {
		return success;
	}

	/** 是否失败。 */
	public boolean isFailure() {
		return !success;
	}

	/** 附加消息（成功时的说明 / 失败时的原因，可能为空字符串）。 */
	public String getMessage() {
		return message;
	}

	@Override
	public String toString() {
		return (success ? "SUCCESS" : "FAILURE") + (message.isEmpty() ? "" : ": " + message);
	}
}
