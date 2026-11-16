package com.onessh.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onessh.app.data.Host;
import com.onessh.app.data.Snippet;
import com.onessh.app.data.SnippetGroup;
import com.onessh.app.data.Store;
import com.onessh.app.ssh.HostKeyPrompt;
import com.onessh.app.ssh.MoshSession;
import com.onessh.app.ssh.SshSession;
import com.onessh.app.term.TermColors;
import com.onessh.app.term.TerminalView;
import com.onessh.app.ui.Dialogs;
import com.onessh.app.ui.UI;
import com.onessh.app.ui.Widgets;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 终端页：连上之后这里就是一台真的终端。 */
public class TerminalActivity extends Activity implements TerminalView.Listener {

    public static final String EXTRA_HOST_ID = "hostId";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASSWORD = "password";
    /** 调试用：不连服务器，直接灌一段示例输出 */
    public static final String EXTRA_DEMO = "demo";

    private Store store;
    private Host host;
    private String hostId;
    private SshSession session;
    private MoshSession moshSession;

    private TerminalView term;
    private LinearLayout overlay;
    private LinearLayout selectionBar;
    private LinearLayout keysBar;
    private TextView ctrlKey;
    private TextView titleText;
    private TextView statusText;
    private View statusDot;

    private String adHocHost;
    private int adHocPort = 22;
    private String adHocUser;
    private String adHocPassword;

    private boolean keysVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);

        hostId = getIntent().getStringExtra(EXTRA_HOST_ID);
        if (hostId != null && !hostId.isEmpty()) {
            host = store.host(hostId);
        } else {
            adHocHost = getIntent().getStringExtra(EXTRA_HOST);
            adHocPort = getIntent().getIntExtra(EXTRA_PORT, 22);
            adHocUser = getIntent().getStringExtra(EXTRA_USER);
            adHocPassword = getIntent().getStringExtra(EXTRA_PASSWORD);
        }

        if (store.keepAwake()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UI.TERM_BG);

        LinearLayout content = UI.column(this);
        content.setBackgroundColor(UI.TERM_BG);
        content.addView(buildBar());

        FrameLayout termWrap = new FrameLayout(this);
        termWrap.setPadding(UI.dp(6), UI.dp(4), UI.dp(4), UI.dp(2));
        term = new TerminalView(this);
        term.setFontSizeSp(store.fontSize());
        term.setListener(this);
        termWrap.addView(term, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(termWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 浮层只盖住终端区域：顶栏的状态和底部按键仍然可见
        overlay = UI.column(this);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xF2070910);
        termWrap.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 文字选中的操作栏：平时藏着，进选中模式才贴在终端底部
        selectionBar = buildSelectionBar();
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM;
        slp.leftMargin = UI.dp(12);
        slp.rightMargin = UI.dp(12);
        slp.bottomMargin = UI.dp(10);
        termWrap.addView(selectionBar, slp);
        selectionBar.setVisibility(View.GONE);

        keysBar = buildKeys();
        content.addView(keysBar);

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        if (getIntent().getBooleanExtra(EXTRA_DEMO, false)) {
            term.post(this::runDemo);
            return;
        }

        showOverlay("connecting", getString(R.string.connecting), null);
        // 等布局完成再连，这样 cols/rows 是真实值
        term.post(this::connect);
    }


    /* ------------------------------------------------------------- 演示 */

    /** 不连服务器，直接喂一段带颜色、框线、中文的输出，用来核对渲染。 */
    private void runDemo() {
        String[] lines = {
                "\u001b[1;32mdeploy@prod-web\u001b[0m:\u001b[1;34m~/srv/app\u001b[0m$ ls -la",
                "total 48",
                "drwxr-xr-x  6 deploy deploy 4096 Nov 14 10:02 \u001b[1;34m.\u001b[0m",
                "drwxr-xr-x  3 root   root   4096 Nov 12 09:31 \u001b[1;34m..\u001b[0m",
                "-rw-r--r--  1 deploy deploy  812 Nov 14 09:58 \u001b[32mapp.log\u001b[0m",
                "-rwxr-xr-x  1 deploy deploy 1.2M Nov 13 22:11 \u001b[1;32mserver\u001b[0m",
                "",
                "\u001b[1;32mdeploy@prod-web\u001b[0m:\u001b[1;34m~/srv/app\u001b[0m$ ./server --status",
                "\u001b[1m服务状态\u001b[0m",
                "┌────────────┬──────────┬─────────┐",
                "│ 名称       │ 端口     │ 状态    │",
                "├────────────┼──────────┼─────────┤",
                "│ web        │ 8080     │ \u001b[32m运行中\u001b[0m  │",
                "│ worker     │ 9090     │ \u001b[33m等待中\u001b[0m  │",
                "│ scheduler  │ 7070     │ \u001b[31m已停止\u001b[0m  │",
                "└────────────┴──────────┴─────────┘",
                "",
                "\u001b[36mINFO\u001b[0m  2026-11-14 11:02:31  健康检查通过",
                "\u001b[33mWARN\u001b[0m  2026-11-14 11:02:33  队列积压 128 条",
                "\u001b[31mERROR\u001b[0m 2026-11-14 11:02:35  上游 10.0.3.7 连接超时",
                "",
                "\u001b[1m进度\u001b[0m [\u001b[32m████████████████\u001b[0m░░░░] 78%",
                "",
                "\u001b[4m带下划线的链接\u001b[0m  \u001b[7m反色高亮\u001b[0m  \u001b[2m暗淡文字\u001b[0m",
                "\u001b[38;5;208m256 色：\u001b[38;5;208m#ff8700\u001b[0m  \u001b[38;2;120;180;255m真彩：rgb(120,180,255)\u001b[0m",
                "",
                "\u001b[1;32mdeploy@prod-web\u001b[0m:\u001b[1;34m~/srv/app\u001b[0m$ ",
        };
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\r\n");
        term.feed(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (titleText != null) titleText.setText("示例终端");
        setStatus("connected", getString(R.string.connected) + "（示例）");
        hideOverlay();
        UI.toast(this, "示例输出，没有真实连接");
    }

    /* ------------------------------------------------------------- 顶栏 */

    private View buildBar() {
        LinearLayout bar = UI.row(this);
        bar.setBackgroundColor(UI.TERM_BAR);
        bar.setPadding(UI.dp(4), UI.dp(6), UI.dp(4), UI.dp(6));

        View back = Widgets.barIcon(this, "back", UI.TERM_FG, () -> {
            if (anyConnected()) askDisconnect();
            else finish();
        });
        bar.addView(back);

        LinearLayout titles = UI.column(this);
        String name = host != null ? host.label() : (adHocUser + "@" + adHocHost);
        titleText = UI.medium(this, name, 13.5f, UI.TERM_FG);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleText);

        LinearLayout statusRow = UI.row(this);
        statusDot = new View(this);
        statusDot.setBackground(UI.bg(UI.AMBER, 3f));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(UI.dp(6), UI.dp(6));
        dp.rightMargin = UI.dp(6);
        statusDot.setLayoutParams(dp);
        statusRow.addView(statusDot);

        statusText = UI.text(this, getString(R.string.connecting), 10f, UI.TERM_MUTED);
        statusRow.addView(statusText);
        Widgets.top(statusRow, 3);
        titles.addView(statusRow);

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = UI.dp(4);
        bar.addView(titles, tp);

        bar.addView(Widgets.barIcon(this, "folder", UI.TERM_MUTED, this::openFiles));
        bar.addView(Widgets.barIcon(this, "terminal", UI.TERM_MUTED, () -> {
            keysVisible = !keysVisible;
            keysBar.setVisibility(keysVisible ? View.VISIBLE : View.GONE);
        }));
        bar.addView(Widgets.barIcon(this, "code", UI.TERM_MUTED, this::snippetSheet));
        bar.addView(Widgets.barIcon(this, "more", UI.TERM_MUTED, this::moreMenu));
        return bar;
    }

    private void setStatus(String state, String text) {
        if (statusText != null) statusText.setText(text);
        if (statusDot != null) {
            int color = UI.AMBER;
            if ("connected".equals(state)) color = UI.GREEN;
            else if ("error".equals(state)) color = UI.RED;
            else if ("closed".equals(state)) color = UI.TERM_MUTED;
            statusDot.setBackground(UI.bg(color, 3f));
        }
    }

    /* ----------------------------------------------------------- 扩展键 */

    private LinearLayout buildKeys() {
        LinearLayout wrap = UI.column(this);
        wrap.setBackgroundColor(UI.TERM_BAR);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = UI.row(this);
        row.setPadding(UI.dp(4), UI.dp(5), UI.dp(4), UI.dp(5));

        row.addView(key("Esc", "\u001b"));
        row.addView(key("Tab", "\t"));
        row.addView(keyCtrl());
        row.addView(key("←", "\u001b[D"));
        row.addView(key("↑", "\u001b[A"));
        row.addView(key("↓", "\u001b[B"));
        row.addView(key("→", "\u001b[C"));
        row.addView(key("Home", "\u001b[H"));
        row.addView(key("End", "\u001b[F"));
        row.addView(key("PgUp", "\u001b[5~"));
        row.addView(key("PgDn", "\u001b[6~"));
        row.addView(key("-", "-"));
        row.addView(key("/", "/"));
        row.addView(key("|", "|"));
        row.addView(key("~", "~"));
        row.addView(key("*", "*"));

        hs.addView(row);
        wrap.addView(hs);
        wrap.addView(UI.hline(this, UI.TERM_LINE));
        return wrap;
    }

    /** Ctrl 键：点一下等于按住，再点一下松开；长按仍然可以调出字母盘 */
    private View keyCtrl() {
        ctrlKey = UI.mono(this, "Ctrl", 11.5f, UI.TERM_FG);
        ctrlKey.setGravity(Gravity.CENTER);
        ctrlKey.setBackground(UI.ripple(UI.bg(UI.TERM_LINE, 6f), 6f));
        ctrlKey.setPadding(UI.dp(10), UI.dp(8), UI.dp(10), UI.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(5);
        ctrlKey.setLayoutParams(lp);
        ctrlKey.setOnClickListener(v -> term.toggleCtrlLatch());
        ctrlKey.setOnLongClickListener(v -> {
            ctrlSheet();
            return true;
        });
        return ctrlKey;
    }

    @Override
    public void onCtrlLatch(boolean on) {
        if (ctrlKey == null) return;
        ctrlKey.setBackground(UI.ripple(UI.bg(on ? UI.ACCENT : UI.TERM_LINE, 6f), 6f));
        ctrlKey.setTextColor(on ? 0xFFFFFFFF : UI.TERM_FG);
        if (on) UI.toast(this, "Ctrl 已按住，接着按 C / D / Z");
    }

    private View key(String label, String seq) {
        TextView t = UI.mono(this, label, 11.5f, UI.TERM_FG);
        t.setGravity(Gravity.CENTER);
        t.setBackground(UI.ripple(UI.bg(UI.TERM_LINE, 6f), 6f));
        t.setPadding(UI.dp(10), UI.dp(8), UI.dp(10), UI.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(5);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            if (seq == null) ctrlSheet();
            else {
                term.scrollToBottom();
                write(seq);
            }
        });
        return t;
    }

    /** Ctrl 组合：手机键盘上按不出来，给一个字母盘直接发控制字符 */
    private void ctrlSheet() {
        LinearLayout grid = UI.column(this);
        String[] rows = {"ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ[\\]^_"};
        for (String r : rows) {
            LinearLayout row = UI.row(this);
            for (int i = 0; i < r.length(); i++) {
                final char c = r.charAt(i);
                TextView k = UI.mono(this, "C-" + c, 11f, UI.TERM_FG);
                k.setGravity(Gravity.CENTER);
                k.setBackground(UI.ripple(UI.bg(UI.PAPER2, 6f, UI.LINE, 1f), 6f));
                k.setPadding(0, UI.dp(9), 0, UI.dp(9));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = UI.dp(4);
                k.setLayoutParams(lp);
                k.setOnClickListener(v -> {
                    int code = Character.toUpperCase(c) - 'A' + 1;
                    if (c == '[') code = 27;
                    else if (c == '\\') code = 28;
                    else if (c == ']') code = 29;
                    else if (c == '^') code = 30;
                    else if (c == '_') code = 31;
                    write(String.valueOf((char) code));
                });
                row.addView(k);
            }
            Widgets.top(row, 4);
            grid.addView(row);
        }
        Dialogs.custom(this, "Ctrl 组合键", "例如 C-C 中断当前命令，C-D 退出。",
                grid, "关闭", null, false, (Runnable) null);
    }

    /* ------------------------------------------------------------- 连接 */

    private SshSession.Config buildConfig() {
        SshSession.Config cfg;
        if (host != null) {
            cfg = SshSession.fromHost(store, host);
        } else {
            cfg = new SshSession.Config();
            cfg.host = adHocHost;
            cfg.port = adHocPort;
            cfg.user = adHocUser;
            cfg.password = adHocPassword == null || adHocPassword.isEmpty() ? null : adHocPassword;
        }
        if (term != null && term.buffer() != null) {
            cfg.cols = term.buffer().cols;
            cfg.rows = term.buffer().rows;
        }
        return cfg;
    }

    private void connect() {
        if (session != null) session.close();
        if (moshSession != null) moshSession.close();
        session = null;
        moshSession = null;
        showOverlay("connecting", getString(R.string.connecting), null);
        setStatus("connecting", getString(R.string.connecting));

        SshSession.Config cfg = buildConfig();
        if (cfg.host == null || cfg.host.isEmpty()) {
            showOverlay("error", "没有可连接的地址", null);
            return;
        }

        // SSH 和 Mosh 共用同一套输出/状态回调，终端侧不用区分走哪条路
        SshSession.Listener listener = new SshSession.Listener() {
            @Override
            public void onOutput(byte[] data) {
                if (term != null) term.feed(data);
            }

            @Override
            public void onState(String state, String message) {
                runOnUiThread(() -> handleState(state, message));
            }
        };

        if (host != null && host.mosh) {
            moshSession = new MoshSession(this, cfg, listener, this::verifyHostKey);
            Thread t = new Thread(moshSession::connect, "mosh-connect");
            t.setDaemon(true);
            t.start();
            return;
        }

        session = new SshSession(cfg, listener, this::verifyHostKey);

        Thread t = new Thread(session::connect, "ssh-connect");
        t.setDaemon(true);
        t.start();
    }

    private void handleState(String state, String message) {
        if (isFinishing()) return;
        switch (state) {
            case "connected":
                hideOverlay();
                setStatus("connected", moshSession != null
                        ? getString(R.string.connected) + " · Mosh"
                        : getString(R.string.connected));
                if (host != null) store.touchHost(host.id);
                break;
            case "closed":
                SessionRegistry.clear();
                setStatus("closed", getString(R.string.disconnected));
                showOverlay("closed", getString(R.string.disconnected),
                        "会话已结束。可以重新连接。");
                break;
            case "error":
                SessionRegistry.clear();
                setStatus("error", getString(R.string.disconnected));
                showOverlay("error", "连接失败", message);
                break;
            default:
                setStatus(state, getString(R.string.connecting));
                break;
        }
    }

    /* --------------------------------------------------------- 主机密钥 */

    /** 在 SSH 线程上被同步调用，交给 HostKeyPrompt 弹窗并阻塞等待。 */
    private boolean verifyHostKey(String hostPort, String fingerprint, String known) {
        return HostKeyPrompt.ask(this, hostPort, fingerprint, known);
    }

    /* ------------------------------------------------------------- 交互 */

    @Override
    public void onInput(byte[] data) {
        if (moshSession != null) moshSession.write(data);
        else if (session != null) session.write(data);
    }

    @Override
    public void onResize(int cols, int rows) {
        if (moshSession != null) moshSession.resize(cols, rows);
        else if (session != null) session.resize(cols, rows);
    }

    private void write(String s) {
        if (moshSession != null) {
            moshSession.write(s.getBytes(StandardCharsets.UTF_8));
        } else if (session != null) {
            session.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** SSH 或 Mosh 任一会话连着就算连上 */
    private boolean anyConnected() {
        return (session != null && session.isConnected())
                || (moshSession != null && moshSession.isConnected());
    }

    private void moreMenu() {
        Dialogs.sheet(this, host != null ? host.label() : adHocHost,
                new String[]{"最小化", "复制全部输出", "清屏", "终端字号", "mosh测试", "重新连接", "断开连接"},
                new String[]{"minimize", "copy", "trash", "gear", "bolt", "refresh", "power"}, 6, idx -> {
                    switch (idx) {
                        case 0:
                            minimize();
                            break;
                        case 1:
                            copyAll();
                            break;
                        case 2:
                            term.clearScreen();
                            break;
                        case 3:
                            fontSheet();
                            break;
                        case 4:
                            moshTest();
                            break;
                        case 5:
                            term.clearScreen();
                            connect();
                            break;
                        default:
                            askDisconnect();
                            break;
                    }
                });
    }

    /** mosh 测试用的引导命令：启动一个 mosh-server，确认能起来就立刻杀掉，不留残留 */
    private static final String MOSH_TEST_CMD =
            "mosh-server new -s -c 256 -l en_US.UTF-8 >/tmp/.mosh-test.out 2>&1 & echo $! >/tmp/.mosh-test.pid; "
            + "sleep 3; cat /tmp/.mosh-test.out 2>/dev/null; "
            + "kill -9 $(cat /tmp/.mosh-test.pid 2>/dev/null) 2>/dev/null; rm -f /tmp/.mosh-test.out /tmp/.mosh-test.pid";

    /** 测试失败时「mosh测试命令行」发的诊断命令：mosh-server、locale、UDP 监听、防火墙 */
    private static final String MOSH_DIAG_CMD =
            "echo '=== mosh-server ==='; command -v mosh-server >/dev/null 2>&1 && mosh-server --version || echo '未安装 mosh-server'; "
            + "echo '=== locale ==='; locale 2>/dev/null | grep -iE '^(LANG|LC_CTYPE)='; "
            + "echo '=== UDP 监听 ==='; (command -v ss >/dev/null 2>&1 && ss -uln || netstat -uln 2>/dev/null) | grep -E '6000[0-9]|61000' || echo '无 UDP 60000-61000 监听（mosh-server 未启动时正常）'; "
            + "echo '=== 防火墙 ==='; command -v ufw >/dev/null 2>&1 && sudo ufw status | head -20; "
            + "command -v firewall-cmd >/dev/null 2>&1 && sudo firewall-cmd --list-ports; "
            + "command -v iptables >/dev/null 2>&1 && sudo iptables -L INPUT -n | grep -iE 'udp|60000' | head";

    /** 测试失败时「安装mosh」发的命令：按发行版装 mosh，并放行 UDP 60000-61000 */
    private static final String MOSH_INSTALL_CMD =
            "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y mosh; "
            + "elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y mosh; "
            + "elif command -v yum >/dev/null 2>&1; then sudo yum install -y mosh; "
            + "elif command -v pacman >/dev/null 2>&1; then sudo pacman -Sy --noconfirm mosh; "
            + "elif command -v apk >/dev/null 2>&1; then sudo apk add mosh; "
            + "else echo '未找到 apt/dnf/yum/pacman/apk，请手动安装 mosh-server'; fi; "
            + "command -v ufw >/dev/null 2>&1 && sudo ufw allow 60000:61000/udp; "
            + "command -v firewall-cmd >/dev/null 2>&1 && sudo firewall-cmd --permanent --add-port=60000-61000/udp && sudo firewall-cmd --reload; "
            + "command -v iptables >/dev/null 2>&1 && sudo iptables -I INPUT -p udp --dport 60000:61000 -j ACCEPT";

    /**
     * ⋮ 菜单里的 mosh 测试：在已连的 SSH 会话上跑一条 exec，看 mosh-server 能不能起来。
     * 通过只报一声；失败弹原因，带两个按钮把诊断 / 安装命令发到当前会话里执行。
     * 已在用 Mosh 时不用测；没连上时也测不了。
     */
    private void moshTest() {
        if (moshSession != null && moshSession.isConnected()) {
            UI.toast(this, "当前就是 Mosh 会话，服务器没问题");
            return;
        }
        if (session == null || !session.isConnected()) {
            UI.toast(this, "还没连上，等连接成功再测");
            return;
        }
        UI.toast(this, "正在测试 mosh-server…");
        final SshSession s = session;
        new Thread(() -> {
            String fail = null;
            try {
                String output = s.exec(MOSH_TEST_CMD, 15000);
                if (!MoshSession.containsMoshConnect(output)) {
                    fail = MoshSession.classifyBootstrapFailure(output, -1);
                }
            } catch (Exception e) {
                fail = "测试没跑起来：" + e.getMessage();
            }
            final String reason = fail;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (reason == null) {
                    UI.toast(this, "Mosh 测试通过：服务器可以启动 mosh-server");
                } else {
                    showMoshTestFailed(reason);
                }
            });
        }, "mosh-test").start();
    }

    /** 测试失败：给两个按钮，把诊断 / 安装命令发到当前会话里执行 */
    private void showMoshTestFailed(String reason) {
        Dialogs.sheet(this, reason,
                new String[]{"mosh测试命令行", "安装mosh"},
                new String[]{"terminal", "download"}, -1, idx -> {
                    if (idx == 0) {
                        write(MOSH_DIAG_CMD + "\r");
                    } else {
                        write(MOSH_INSTALL_CMD + "\r");
                    }
                });
    }

    /**
     * 最小化：会话不断，只是把主机列表带到前台。
     * TerminalActivity 退到后台栈里（不走 onDestroy），SshSession 的线程
     * 继续跑，输出继续进 TerminalView 的缓冲，回来后原样都在。
     * 只有连上之后才能最小化；还没连上时点了也没意义。
     */
    private void minimize() {
        if (!anyConnected()) {
            UI.toast(this, "还没连上，等连接成功再最小化");
            return;
        }
        SessionRegistry.minimize(this,
                host != null ? host.label() : adHocUser + "@" + adHocHost,
                restoreIntent());
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    /** 回到终端用的 Intent：页面还在就把它带到前台；万一已被系统回收， */
    private Intent restoreIntent() {
        Intent i = new Intent(this, TerminalActivity.class);
        if (hostId != null && !hostId.isEmpty()) {
            i.putExtra(EXTRA_HOST_ID, hostId);
        } else {
            i.putExtra(EXTRA_HOST, adHocHost);
            i.putExtra(EXTRA_PORT, adHocPort);
            i.putExtra(EXTRA_USER, adHocUser);
            i.putExtra(EXTRA_PASSWORD, adHocPassword);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return i;
    }

    /** 主机列表切到别的主机时用：清登记再 finish，onDestroy 会关掉旧会话 */
    public void finishMinimized() {
        SessionRegistry.clear();
        finish();
    }

    /**
     * 代码片段：点一行就发到当前会话；每行右边的 ⋮ 可以发送 / 编辑 / 删除；
     * 底部「新增片段」直接在这里建，不用退回主机列表再进片段管理。
     * 编辑器和保存规则跟 SnippetsActivity 是一套（命令必填，名字空了就
     * 用命令第一行兜底），存在同一个 Store 里，两边看到的是同一份。
     */
    private void snippetSheet() {
        if (term != null) term.clearCtrlLatch();
        List<Snippet> list = store.snippets();
        List<SnippetGroup> groups = store.snippetGroups();

        final Dialog[] sheet = new Dialog[1];
        LinearLayout content = UI.column(this);

        if (list.isEmpty()) {
            TextView hint = UI.text(this, "还没有代码片段，先建一个，之后在这里一键发送。",
                    12.5f, UI.MUTED);
            hint.setLineSpacing(UI.dp(3), 1f);
            content.addView(hint);
        } else {
            ScrollView sc = new ScrollView(this);
            sc.setVerticalScrollBarEnabled(false);
            LinearLayout rows = UI.column(this);
            // 按分组分段：先是各分组（按创建时间），最后是未分组
            Map<String, List<Snippet>> byGroup = new LinkedHashMap<>();
            List<Snippet> ungrouped = new ArrayList<>();
            for (Snippet sn : list) {
                if (sn.groupKey != null && !sn.groupKey.isEmpty()
                        && store.snippetGroup(sn.groupKey) != null) {
                    List<Snippet> g = byGroup.get(sn.groupKey);
                    if (g == null) {
                        g = new ArrayList<>();
                        byGroup.put(sn.groupKey, g);
                    }
                    g.add(sn);
                } else {
                    ungrouped.add(sn);
                }
            }
            for (SnippetGroup g : groups) {
                List<Snippet> inGroup = byGroup.get(g.key);
                if (inGroup == null || inGroup.isEmpty()) continue;
                rows.addView(snippetGroupHeader(g.name, inGroup.size()));
                for (Snippet sn : inGroup) rows.addView(snippetRow(sn, sheet));
            }
            if (!ungrouped.isEmpty()) {
                rows.addView(snippetGroupHeader("未分组", ungrouped.size()));
                for (Snippet sn : ungrouped) rows.addView(snippetRow(sn, sheet));
            }
            sc.addView(rows);
            sc.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(320)));
            content.addView(sc);
        }

        LinearLayout add = Widgets.button(this, "新增片段", "plus", true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = UI.dp(14);
        add.setLayoutParams(ap);
        add.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
            snippetEditor(null);
        });
        content.addView(add);

        sheet[0] = Dialogs.custom(this, "发送代码片段", "点一行直接发送",
                content, "关闭", null, false, (Runnable) null);
    }

    private View snippetGroupHeader(String name, int count) {
        TextView t = UI.text(this, name + "  ·  " + count + " 条", 12f, UI.MUTED);
        t.setTypeface(UI.MEDIUM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UI.dp(8);
        lp.bottomMargin = UI.dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private View snippetRow(Snippet sn, Dialog[] sheet) {
        LinearLayout row = UI.row(this);
        row.setBackground(UI.ripple(UI.PAPER2, 10f));
        UI.padding(row, 12, 11, 12, 11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UI.dp(4);
        row.setLayoutParams(lp);

        LinearLayout col = UI.column(this);
        col.addView(UI.medium(this,
                sn.name == null || sn.name.isEmpty() ? "未命名" : sn.name, 13.5f, UI.INK));
        TextView cmd = UI.mono(this, sn.command.replace("\n", " ⏎ "), 11f, UI.MUTED);
        cmd.setSingleLine(true);
        cmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(cmd);
        if (sn.note != null && !sn.note.isEmpty()) {
            TextView note = UI.text(this, sn.note, 11f, UI.MUTED);
            note.setSingleLine(true);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(note);
        }
        row.addView(col);
        UI.weight(col, 1f);

        row.addView(Widgets.barIcon(this, "more", UI.MUTED, () -> Dialogs.sheet(this, sn.name,
                new String[]{"发送", "编辑", "删除"},
                new String[]{"terminal", "edit", "trash"}, 2, idx -> {
                    switch (idx) {
                        case 0:
                            if (sheet[0] != null) sheet[0].dismiss();
                            sendSnippet(sn);
                            break;
                        case 1:
                            if (sheet[0] != null) sheet[0].dismiss();
                            snippetEditor(sn);
                            break;
                        default:
                            Dialogs.confirm(this, "删除片段",
                                    "确定删除「" + sn.name + "」吗？", "删除", true, () -> {
                                        store.deleteSnippet(sn.id);
                                        if (sheet[0] != null) sheet[0].dismiss();
                                        snippetSheet();
                                    });
                            break;
                    }
                })));

        row.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
            sendSnippet(sn);
        });
        return row;
    }

    private void sendSnippet(Snippet sn) {
        if (term != null) term.scrollToBottom();
        if (sn.onlyInput) {
            // 仅输入：用括号粘贴模式一次填入，shell 只做字面插入、不执行，
            // 多行命令也不会在中途逐行跑起来（bash/zsh 默认都支持）
            write("\u001b[200~" + sn.command + "\u001b[201~");
            UI.toast(this, "已输入（未执行）：" + sn.name);
            return;
        }
        for (String line : sn.command.split("\n")) {
            write(line + "\r");
        }
        UI.toast(this, "已发送：" + sn.name);
    }

    /** 新增 / 编辑片段的弹窗，保存后回到片段列表，改完马上就能发 */
    private void snippetEditor(Snippet snippet) {
        Snippet editing = snippet != null ? snippet : new Snippet();
        final String[] groupKey = {editing.groupKey == null ? "" : editing.groupKey};

        LinearLayout form = UI.column(this);
        Widgets.Field nameField = Widgets.field(this, "名称", "例如：查看磁盘占用",
                InputType.TYPE_CLASS_TEXT);
        nameField.setText(editing.name);
        form.addView(nameField.root);

        Widgets.Field cmdField = Widgets.field(this, "命令",
                "例如：df -h\ntop -b -n1 | head -20",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cmdField.input.setTypeface(UI.MONO);
        cmdField.input.setTextSize(12f);
        cmdField.input.setMinLines(4);
        cmdField.input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cmdField.setText(editing.command);
        Widgets.top(cmdField.root, 12);
        form.addView(cmdField.root);

        Widgets.Field noteField = Widgets.field(this, "备注（可选）", "例如：先装 speedtest-cli",
                InputType.TYPE_CLASS_TEXT);
        noteField.setText(editing.note == null ? "" : editing.note);
        Widgets.top(noteField.root, 12);
        form.addView(noteField.root);

        // 分组行：点开选分组，选完直接改这行的值，不用重开编辑器
        final TextView[] groupValue = new TextView[1];
        LinearLayout groupRow = Widgets.pickRow(this, "folder",
                "分组", groupName(groupKey[0]), null);
        groupValue[0] = findValueText(groupRow);
        groupRow.setOnClickListener(v -> pickSnippetGroup(groupKey, groupValue[0]));
        Widgets.top(groupRow, 12);
        form.addView(groupRow);

        LinearLayout onlyRow = UI.row(this);
        onlyRow.setGravity(Gravity.CENTER_VERTICAL);
        Widgets.top(onlyRow, 14);
        LinearLayout col = UI.column(this);
        col.addView(UI.medium(this, "仅输入，不执行", 13.5f, UI.INK));
        TextView onlyHint = UI.text(this, "只把命令填到输入框，不按回车，适合要改几个参数再跑的命令。",
                11.5f, UI.MUTED);
        onlyHint.setLineSpacing(UI.dp(3), 1f);
        Widgets.top(onlyHint, 3);
        col.addView(onlyHint);
        onlyRow.addView(col);
        UI.weight(col, 1f);
        final Widgets.Toggle onlyInputToggle = new Widgets.Toggle(this, editing.onlyInput);
        onlyRow.addView(onlyInputToggle);
        form.addView(onlyRow);

        Dialogs.custom(this,
                snippet == null || snippet.id == null || snippet.id.isEmpty()
                        ? "新增片段" : "编辑片段",
                "命令按原样发送（末尾会自动补一个回车）。多行命令会逐行发送。",
                form, "保存", "取消", false, () -> {
                    String name = nameField.text().trim();
                    String command = cmdField.text().trim();
                    if (command.isEmpty()) {
                        UI.toast(this, "命令不能为空");
                        return false;
                    }
                    if (name.isEmpty()) {
                        String first = command.split("\n")[0].trim();
                        name = first.length() > 24 ? first.substring(0, 24) + "…" : first;
                    }
                    editing.name = name;
                    editing.command = command;
                    editing.groupKey = groupKey[0];
                    editing.note = noteField.text().trim();
                    editing.onlyInput = onlyInputToggle.isChecked();
                    store.saveSnippet(editing);
                    UI.toast(this, "已保存");
                    snippetSheet();
                    return true;
                });
    }

    /** pickRow 里放值的那个 TextView（列里最后一个 TextView，前面是小字 label） */
    private TextView findValueText(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout col = (LinearLayout) child;
                TextView found = null;
                for (int j = 0; j < col.getChildCount(); j++) {
                    if (col.getChildAt(j) instanceof TextView) {
                        found = (TextView) col.getChildAt(j);
                    }
                }
                if (found != null) return found;
            }
        }
        return null;
    }

    private String groupName(String key) {
        if (key == null || key.isEmpty()) return "未分组";
        SnippetGroup g = store.snippetGroup(key);
        return g == null ? "未分组" : g.name;
    }

    /** 选分组：未分组 / 已有分组 / 新建分组，选完直接改行上的值 */
    private void pickSnippetGroup(final String[] groupKey, final TextView valueView) {
        List<SnippetGroup> groups = store.snippetGroups();
        List<String> labels = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        labels.add("未分组");
        subs.add("不放在任何分组里");
        int selected = 0;
        for (int i = 0; i < groups.size(); i++) {
            SnippetGroup g = groups.get(i);
            labels.add(g.name);
            int n = 0;
            for (Snippet s : store.snippets()) if (g.key.equals(s.groupKey)) n++;
            subs.add(n + " 条片段");
            if (g.key.equals(groupKey[0])) selected = i + 1;
        }
        labels.add("＋ 新建分组…");
        subs.add(null);
        Dialogs.choose(this, "选择分组",
                labels.toArray(new String[0]), subs.toArray(new String[0]),
                selected, idx -> {
                    if (idx == labels.size() - 1) {
                        Dialogs.prompt(this, "新建分组", "分组名称", "例如：网络",
                                "", false, text -> {
                                    String n = text == null ? "" : text.trim();
                                    if (n.isEmpty()) {
                                        UI.toast(this, "分组名称不能为空");
                                        return;
                                    }
                                    SnippetGroup g = new SnippetGroup();
                                    g.name = n;
                                    store.saveSnippetGroup(g);
                                    groupKey[0] = g.key;
                                    if (valueView != null) valueView.setText(g.name);
                                });
                    } else if (idx == 0) {
                        groupKey[0] = "";
                        if (valueView != null) valueView.setText("未分组");
                    } else {
                        groupKey[0] = groups.get(idx - 1).key;
                        if (valueView != null) valueView.setText(groups.get(idx - 1).name);
                    }
                });
    }

    /** 长按一行后“加片段”：命令框里直接填好按住的那行，名字空着，保存时自动用命令第一行当名字 */
    private void snippetEditorNew(String command) {
        Snippet fresh = new Snippet();
        fresh.command = command;
        snippetEditor(fresh);
    }

    /** 长按终端：高亮按住的那一行，弹菜单让用户复制该行、拖选文字、复制全部输出，或把它存成片段 */
    @Override
    public void onLongPress(float x, float y) {
        if (term == null) return;
        String line = term.lineTextAt(y);
        if (line == null || line.trim().isEmpty()) {
            // 空行上长按：只能从这里开始拖选文字
            Dialogs.sheet(this, "这一行是空的",
                    new String[]{"复制文字"},
                    new String[]{"copy"}, -1, idx -> {
                        term.clearSelection();
                        term.startTextSelection(x, y);
                    });
            return;
        }
        String preview = line.length() > 40 ? line.substring(0, 40) + "…" : line;
        Dialogs.sheet(this, preview,
                new String[]{"复制该行", "复制文字", "复制全部输出", "加片段"},
                new String[]{"copy", "copy", "copy", "plus"}, -1, idx -> {
                    term.clearSelection();
                    switch (idx) {
                        case 0:
                            copyText(line, "已复制该行");
                            break;
                        case 1:
                            term.startTextSelection(x, y);
                            break;
                        case 2:
                            copyAll();
                            break;
                        default:
                            snippetEditorNew(line.trim());
                            break;
                    }
                });
    }

    /** 文字选中模式的开关：进模式显示底部复制栏，退出就收起来 */
    @Override
    public void onTextSelection(boolean active) {
        if (selectionBar != null) {
            selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout buildSelectionBar() {
        LinearLayout bar = UI.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(UI.bg(UI.CARD, 12f, UI.LINE, 1f));
        bar.setElevation(UI.dp(4f));
        UI.padding(bar, 12, 10, 12, 10);

        TextView hint = UI.text(this, "拖动两端圆点调整选区", 12f, UI.MUTED);
        bar.addView(hint);
        UI.weight(hint, 1f);

        LinearLayout copy = Widgets.button(this, "复制", "copy", true);
        copy.setOnClickListener(v -> copySelection());
        bar.addView(copy);

        LinearLayout cancel = Widgets.button(this, "取消", null, false);
        Widgets.margins(cancel, 8, 0, 0, 0);
        cancel.setOnClickListener(v -> {
            if (term != null) term.clearTextSelection();
        });
        bar.addView(cancel);
        return bar;
    }

    private void copySelection() {
        if (term == null) return;
        String text = term.selectedText();
        if (text == null || text.isEmpty()) {
            UI.toast(this, "选中的内容是空的");
            return;
        }
        copyText(text, "已复制");
        term.clearTextSelection();
    }

    private void copyText(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        UI.toast(this, toast);
    }

    private void copyAll() {
        String text = term.allText();
        copyText(text, "已复制 " + text.length() + " 个字符");
    }

    private void fontSheet() {
        final int[] sizes = {11, 13, 15, 17, 20};
        String[] labels = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) labels[i] = sizes[i] + " sp";
        Dialogs.sheet(this, "终端字号", labels, null, -1, idx -> {
            term.setFontSizeSp(sizes[idx]);
            store.setFontSize(sizes[idx]);
            term.post(() -> {
                if (term.buffer() == null) return;
                int cols = term.buffer().cols;
                int rows = term.buffer().rows;
                if (moshSession != null) moshSession.resize(cols, rows);
                else if (session != null) session.resize(cols, rows);
            });
        });
    }

    private void openFiles() {
        if (host == null) {
            UI.toast(this, "快速连接不支持文件管理，先把它保存成主机");
            return;
        }
        Intent i = new Intent(this, FilesActivity.class);
        i.putExtra(FilesActivity.EXTRA_HOST_ID, host.id);
        startActivity(i);
    }

    private void askDisconnect() {
        Dialogs.confirm(this, "断开连接", "当前会话会立即结束，远端正在跑的任务可能被中断。",
                "断开", true, () -> {
                    if (session != null) session.close();
                    if (moshSession != null) moshSession.close();
                    finish();
                });
    }

    @Override
    public void onBackPressed() {
        if (anyConnected()) askDisconnect();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // 最小化时不走这里；真走到这里就是页面销毁，会话跟着关，
        // 登记也要清掉，主机列表上的悬浮卡片才不会变成死按钮。
        SessionRegistry.clearIf(this);
        if (session != null) session.close();
        if (moshSession != null) moshSession.close();
        super.onDestroy();
    }

    /* ------------------------------------------------------------- 浮层 */

    private void showOverlay(String state, String title, String detail) {
        if (overlay == null) return;
        // 连接状态变了，正在选的文字就作废，复制栏也收起来
        if (term != null) term.clearTextSelection();
        overlay.removeAllViews();
        overlay.setVisibility(View.VISIBLE);

        if ("connecting".equals(state)) {
            ProgressBar pb = new ProgressBar(this);
            pb.getIndeterminateDrawable().setTint(UI.ACCENT);
            overlay.addView(pb, new LinearLayout.LayoutParams(UI.dp(30), UI.dp(30)));
        }

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = "connecting".equals(state) ? UI.dp(16) : 0;
        tp.leftMargin = UI.dp(36);
        tp.rightMargin = UI.dp(36);
        TextView t = UI.medium(this, title, 14f, TermColors.FG);
        t.setGravity(Gravity.CENTER);
        overlay.addView(t, tp);

        if (detail != null && !detail.isEmpty()) {
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp.topMargin = UI.dp(8);
            dp.leftMargin = UI.dp(36);
            dp.rightMargin = UI.dp(36);
            TextView d = UI.text(this, detail, 12f, UI.TERM_MUTED);
            d.setGravity(Gravity.CENTER);
            d.setLineSpacing(UI.dp(3), 1f);
            overlay.addView(d, dp);
        }

        if (!"connecting".equals(state)) {
            LinearLayout retry = Widgets.button(this, getString(R.string.reconnect),
                    "refresh", true);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = UI.dp(24);
            retry.setOnClickListener(v -> {
                term.clearScreen();
                connect();
            });
            overlay.addView(retry, rp);

            LinearLayout close = Widgets.link(this, "返回主机列表", null, UI.TERM_MUTED);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = UI.dp(6);
            close.setOnClickListener(v -> finish());
            overlay.addView(close, cp);
        }
    }

    private void hideOverlay() {
        if (overlay != null) overlay.setVisibility(View.GONE);
        if (term != null) term.scrollToBottom();
    }
}
