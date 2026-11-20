package chat.common;

/**
 * 应用层协议定义与工具方法。
 * 所有消息为一行 UTF-8 文本，字段以 | 分隔，回车换行结束。
 */
public final class Protocol {

    /** 服务器默认监听端口 */
    public static final int DEFAULT_PORT = 8888;

    /** 服务器最大同时在线人数 */
    public static final int MAX_USERS = 100;

    /** 客户端心跳发送间隔（毫秒） */
    public static final long CLIENT_PING_INTERVAL_MS = 10_000;

    /** 客户端判定服务器失联的超时（毫秒） */
    public static final long CLIENT_PING_TIMEOUT_MS = 30_000;

    /** 服务器心跳超时（毫秒）：超时未收到任何消息即判定掉线 */
    public static final long SERVER_HEARTBEAT_TIMEOUT_MS = 30_000;

    /** 昵称最大长度 */
    public static final int MAX_NAME_LEN = 16;

    // ---- 客户端 → 服务器 ----
    public static final String CMD_LOGIN = "LOGIN";
    public static final String CMD_CHAT = "CHAT";
    public static final String CMD_PRIVATE = "PRIVATE";
    public static final String CMD_PING = "PING";
    public static final String CMD_LOGOUT = "LOGOUT";

    // ---- 服务器 → 客户端 ----
    public static final String CMD_WELCOME = "WELCOME";
    public static final String CMD_USERLIST = "USERLIST";
    public static final String CMD_SYSTEM = "SYSTEM";
    public static final String CMD_PONG = "PONG";
    public static final String CMD_ERROR = "ERROR";

    private Protocol() {
    }

    /**
     * 校验昵称合法性。
     *
     * @param raw 原始输入（允许首尾空白，将被 trim）
     * @return null 表示合法；否则返回错误原因
     */
    public static String validateName(String raw) {
        if (raw == null) {
            return "昵称不能为空";
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return "昵称不能为空";
        }
        if (name.length() > MAX_NAME_LEN) {
            return "昵称过长（最多 " + MAX_NAME_LEN + " 个字符）";
        }
        for (char c : name.toCharArray()) {
            if (c == '|' || c == ',' || c == '@') {
                return "昵称不能包含 | , @ 字符";
            }
            if (Character.isWhitespace(c)) {
                return "昵称不能包含空白字符";
            }
        }
        return null;
    }

    /**
     * 按 | 切分协议行，最多 maxParts 段。
     * 超出部分重新拼回最后一段（消息内容允许包含 |）。
     * 不足 maxParts 段返回 null。
     */
    public static String[] split(String line, int maxParts) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length < maxParts) {
            return null;
        }
        if (parts.length > maxParts) {
            String[] result = new String[maxParts];
            System.arraycopy(parts, 0, result, 0, maxParts - 1);
            StringBuilder sb = new StringBuilder(parts[maxParts - 1]);
            for (int i = maxParts; i < parts.length; i++) {
                sb.append('|').append(parts[i]);
            }
            result[maxParts - 1] = sb.toString();
            return result;
        }
        return parts;
    }
}
