package chat.test;

import chat.client.ClientConnector;
import chat.server.ChatServer;

import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 自动化验收测试：对应 docs/test-cases.md 的 T01~T12。
 * 启动内置服务器（测试端口 18888，心跳超时 2 秒），逐条执行并输出 PASS/FAIL 汇总。
 * 全部通过时进程退出码为 0，否则为 1。
 */
public class TestRunner {

    static final String HOST = "127.0.0.1";
    static final int PORT = 18888;
    static final long HB_TIMEOUT_MS = 2000;

    static final List<String[]> results = new ArrayList<>();
    static ChatServer server;

    /** 测试客户端：封装连接器 + 事件队列 + 最近一次在线列表 */
    static class TC {
        final String name;
        final ClientConnector conn;
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        volatile String lastUserList = "";

        TC(String name, boolean autoPing) throws IOException {
            this.name = name;
            this.conn = new ClientConnector();
            this.conn.connect(HOST, PORT, name, new ClientConnector.Listener() {
                @Override
                public void onWelcome(String n) {
                    q.add("WELCOME|" + n);
                }

                @Override
                public void onUserList(List<String> u) {
                    lastUserList = String.join(",", u);
                    q.add("USERLIST|" + lastUserList);
                }

                @Override
                public void onChat(String f, String t) {
                    q.add("CHAT|" + f + "|" + t);
                }

                @Override
                public void onPrivate(String f, String t) {
                    q.add("PRIV|" + f + "|" + t);
                }

                @Override
                public void onSystem(String t) {
                    q.add("SYSTEM|" + t);
                }

                @Override
                public void onError(String t) {
                    q.add("ERROR|" + t);
                }

                @Override
                public void onPong() {
                    q.add("PONG");
                }

                @Override
                public void onDisconnected(String r) {
                    q.add("DISC|" + r);
                }
            }, autoPing, 500);
        }

        /** 在 ms 毫秒内等待一条包含指定子串的事件；期间丢弃不匹配的事件 */
        String expect(String contains, long ms) {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                try {
                    String e = q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (e == null) {
                        return null;
                    }
                    if (e.contains(contains)) {
                        return e;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }

        /** 在 ms 毫秒内断言不会出现包含指定子串的事件 */
        boolean expectNone(String contains, long ms) {
            return expect(contains, ms) == null;
        }

        void close() {
            conn.close();
        }
    }

    static void check(String id, boolean pass, String detail) {
        results.add(new String[]{id, pass ? "PASS" : "FAIL", detail});
    }

    static void settle(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean waitUntil(BooleanSupplier cond, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            settle(50);
        }
        return false;
    }

    static boolean waitUserListContains(TC t, String name, long ms) {
        return waitUntil(() -> t.lastUserList.contains(name), ms);
    }

    static boolean waitUserListAbsent(TC t, String name, long ms) {
        return waitUntil(() -> !t.lastUserList.contains(name) && !t.lastUserList.isEmpty(), ms);
    }

    static void closeAll(List<TC> list) {
        for (TC t : list) {
            t.close();
        }
    }

    // ---------------- 测试用例 ----------------

    static void t01() throws IOException {
        TC a = new TC("T01Alice", true);
        try {
            boolean welcome = a.expect("WELCOME|T01Alice", 2000) != null;
            String ul = a.expect("USERLIST", 2000);
            boolean inList = ul != null && ul.contains("T01Alice");
            check("T01", welcome && inList,
                    "welcome=" + welcome + ", userlist=" + ul);
        } finally {
            a.close();
        }
    }

    static void t02() throws IOException {
        TC a = new TC("T02Alice", true);
        try {
            a.expect("WELCOME", 2000);
            a.expect("USERLIST", 2000);
            ClientConnector b = new ClientConnector();
            boolean rejected = false;
            String msg = "";
            try {
                b.connect(HOST, PORT, "T02Alice", new ClientConnector.Listener() {
                }, true, 500);
            } catch (IOException e) {
                rejected = true;
                msg = e.getMessage();
            }
            a.conn.sendChat("服务器还活着吗");
            boolean alive = a.expect("CHAT|T02Alice|服务器还活着吗", 2000) != null;
            check("T02", rejected && msg.contains("已被使用") && alive,
                    "rejected=" + rejected + ", msg=" + msg + ", serverAlive=" + alive);
        } finally {
            a.close();
        }
    }

    static void t03() throws IOException {
        TC a = new TC("T03Alice", true);
        TC b = new TC("T03Bob", true);
        TC c = new TC("T03Carol", true);
        try {
            a.conn.sendChat("大家好");
            boolean ra = a.expect("CHAT|T03Alice|大家好", 2000) != null;
            boolean rb = b.expect("CHAT|T03Alice|大家好", 2000) != null;
            boolean rc = c.expect("CHAT|T03Alice|大家好", 2000) != null;
            check("T03", ra && rb && rc, "sender=" + ra + ", b=" + rb + ", c=" + rc);
        } finally {
            closeAll(List.of(a, b, c));
        }
    }

    static void t04() throws IOException {
        TC a = new TC("T04Alice", true);
        TC b = new TC("T04Bob", true);
        TC c = new TC("T04Carol", true);
        try {
            a.conn.sendPrivate("T04Bob", "秘密消息");
            boolean got = b.expect("PRIV|T04Alice|秘密消息", 2000) != null;
            boolean leakToC = !c.expectNone("PRIV", 800);
            boolean echoToA = !a.expectNone("PRIV", 800);
            check("T04", got && !leakToC && !echoToA,
                    "bGot=" + got + ", leakToC=" + leakToC + ", echoToA=" + echoToA);
        } finally {
            closeAll(List.of(a, b, c));
        }
    }

    static void t05() throws IOException {
        TC a = new TC("T05Alice", true);
        TC b = new TC("T05Bob", true);
        try {
            TC c = new TC("T05Carol", true);
            try {
                boolean aOk = waitUserListContains(a, "T05Carol", 2000);
                boolean bOk = waitUserListContains(b, "T05Carol", 2000);
                boolean cOk = waitUserListContains(c, "T05Carol", 2000);
                check("T05", aOk && bOk && cOk, "a=" + aOk + ", b=" + bOk + ", c=" + cOk);
            } finally {
                c.close();
            }
        } finally {
            closeAll(List.of(a, b));
        }
    }

    static void t06() throws IOException {
        TC a = new TC("T06Alice", true);
        TC b = new TC("T06Bob", true);
        TC c = new TC("T06Carol", true);
        try {
            b.close();
            boolean sys = a.expect("下线", 2000) != null;
            boolean removedA = waitUserListAbsent(a, "T06Bob", 2000);
            boolean removedC = waitUserListAbsent(c, "T06Bob", 2000);
            check("T06", sys && removedA && removedC,
                    "system=" + sys + ", removedA=" + removedA + ", removedC=" + removedC);
        } finally {
            closeAll(List.of(a, b, c));
        }
    }

    static void t07() throws IOException {
        TC a = new TC("T07Alice", true);
        try {
            a.conn.sendPing();
            boolean pong = a.expect("PONG", 2000) != null;
            settle(2500); // 超过服务器 2 秒心跳超时，靠自动心跳保活
            a.conn.sendChat("心跳保活测试");
            boolean alive = a.expect("CHAT|T07Alice|心跳保活测试", 2000) != null;
            check("T07", pong && alive, "pong=" + pong + ", aliveAfterTimeout=" + alive);
        } finally {
            a.close();
        }
    }

    static void t08() throws IOException {
        TC a = new TC("T08Alice", true);
        TC d = new TC("T08Dormant", false); // 不发送任何消息，模拟进程崩溃
        try {
            boolean sys = a.expect("下线", 5000) != null;
            boolean removed = waitUserListAbsent(a, "T08Dormant", 3000);
            boolean disc = d.expect("DISC", 5000) != null;
            check("T08", sys && removed && disc,
                    "system=" + sys + ", removed=" + removed + ", dormantDisc=" + disc);
        } finally {
            closeAll(List.of(a, d));
        }
    }

    static void t09() throws IOException {
        String[] bad = {"", "   ", "a|b", "a,b", "a@b", "aaaaaaaaaaaaaaaaa"};
        boolean allRejected = true;
        StringBuilder detail = new StringBuilder();
        for (String n : bad) {
            ClientConnector conn = new ClientConnector();
            try {
                conn.connect(HOST, PORT, n, new ClientConnector.Listener() {
                }, true, 500);
                allRejected = false;
                detail.append("[").append(n).append("] 未拒绝; ");
            } catch (IOException e) {
                detail.append("[").append(n).append("] 拒绝: ").append(e.getMessage()).append("; ");
            }
        }
        TC a = new TC("T09Alive", true);
        try {
            a.conn.sendChat("服务器存活检查");
            boolean alive = a.expect("CHAT|T09Alive|服务器存活检查", 2000) != null;
            check("T09", allRejected && alive, detail + " serverAlive=" + alive);
        } finally {
            a.close();
        }
    }

    static void t10() throws IOException {
        TC e = new TC("T10Eva", true);
        try {
            e.conn.sendRaw("PRIVATE");
            e.conn.sendRaw("PRIVATE|Bob");
            e.conn.sendRaw("FOO");
            boolean e1 = e.expect("ERROR", 2000) != null;
            boolean e2 = e.expect("ERROR", 2000) != null;
            boolean e3 = e.expect("ERROR", 2000) != null;
            e.conn.sendChat("服务器还活着吗");
            boolean alive = e.expect("CHAT|T10Eva|服务器还活着吗", 2000) != null;
            check("T10", e1 && e2 && e3 && alive,
                    "err1=" + e1 + ", err2=" + e2 + ", err3=" + e3 + ", alive=" + alive);
        } finally {
            e.close();
        }
    }

    static void t11() throws IOException {
        List<TC> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            list.add(new TC(String.format("U%02d", i), true));
        }
        try {
            boolean allWelcome = true;
            for (TC t : list) {
                if (t.expect("WELCOME", 5000) == null) {
                    allWelcome = false;
                }
            }
            boolean fullList = waitUntil(() -> list.get(0).lastUserList.split(",", -1).length == 30, 5000);
            long start = System.currentTimeMillis();
            list.get(0).conn.sendChat("并发广播测试");
            boolean allGot = true;
            for (TC t : list) {
                if (t.expect("CHAT|U00|并发广播测试", 1000) == null) {
                    allGot = false;
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            check("T11", allWelcome && fullList && allGot && elapsed < 1000,
                    "30并发登录=" + allWelcome + ", 列表满=" + fullList
                            + ", 全收到=" + allGot + ", 广播延迟=" + elapsed + "ms");
        } finally {
            closeAll(list);
        }
    }

    static void t12() {
        ChatServer second = new ChatServer(PORT, HB_TIMEOUT_MS);
        Exception err = startServer(second);
        boolean bindFailed = err instanceof BindException;
        second.close();
        check("T12", bindFailed,
                bindFailed ? "端口被占用时抛出 BindException" : "未按预期拒绝，err=" + err);
    }

    static Exception startServer(ChatServer s) {
        AtomicReference<Exception> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                s.start();
            } catch (Exception e) {
                err.set(e);
            }
        }, "test-server");
        t.setDaemon(true);
        t.start();
        settle(300);
        return err.get();
    }

    // ---------------- 主流程 ----------------

    public static void main(String[] args) throws Exception {
        server = new ChatServer(PORT, HB_TIMEOUT_MS);
        Exception startErr = startServer(server);
        if (startErr != null) {
            System.out.println("[FATAL] 测试服务器启动失败: " + startErr);
            System.exit(1);
        }
        System.out.println("===== 局域网聊天室验收测试开始（端口 " + PORT + "，心跳超时 " + HB_TIMEOUT_MS + "ms）=====");

        try {
            t01();
            settle(300);
            t02();
            settle(300);
            t03();
            settle(300);
            t04();
            settle(300);
            t05();
            settle(300);
            t06();
            settle(300);
            t07();
            settle(300);
            t08();
            settle(300);
            t09();
            settle(300);
            t10();
            settle(300);
            t11();
            settle(300);
            t12();
        } finally {
            server.close();
        }

        int passed = 0;
        System.out.println("\n===== 测试结果汇总 =====");
        for (String[] r : results) {
            System.out.printf("%-6s %-6s %s%n", r[0], r[1], r[2]);
            if ("PASS".equals(r[1])) {
                passed++;
            }
        }
        System.out.println("通过 " + passed + " / " + results.size());
        System.exit(passed == results.size() ? 0 : 1);
    }
}
