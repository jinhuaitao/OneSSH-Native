package com.onessh.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.onessh.app.data.Host;
import com.onessh.app.data.Snippet;
import com.onessh.app.data.Store;
import com.onessh.app.ssh.HostKeyPrompt;
import com.onessh.app.ssh.SshSession;
import com.onessh.app.term.TermColors;
import com.onessh.app.term.TerminalView;
import com.onessh.app.ui.Dialogs;
import com.onessh.app.ui.UI;
import com.onessh.app.ui.Widgets;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    private TerminalView term;
    private LinearLayout overlay;
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
            if (session != null && session.isConnected()) askDisconnect();
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
                grid, "关闭", null, false, null);
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
        showOverlay("connecting", getString(R.string.connecting), null);
        setStatus("connecting", getString(R.string.connecting));

        SshSession.Config cfg = buildConfig();
        if (cfg.host == null || cfg.host.isEmpty()) {
            showOverlay("error", "没有可连接的地址", null);
            return;
        }

        session = new SshSession(cfg, new SshSession.Listener() {
            @Override
            public void onOutput(byte[] data) {
                if (term != null) term.feed(data);
            }

            @Override
            public void onState(String state, String message) {
                runOnUiThread(() -> handleState(state, message));
            }
        }, this::verifyHostKey);

        Thread t = new Thread(session::connect, "ssh-connect");
        t.setDaemon(true);
        t.start();
    }

    private void handleState(String state, String message) {
        if (isFinishing()) return;
        switch (state) {
            case "connected":
                hideOverlay();
                setStatus("connected", getString(R.string.connected));
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
        SshSession s = session;
        if (s != null) s.write(data);
    }

    @Override
    public void onResize(int cols, int rows) {
        SshSession s = session;
        if (s != null) s.resize(cols, rows);
    }

    private void write(String s) {
        SshSession sess = session;
        if (sess == null) return;
        sess.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private void moreMenu() {
        Dialogs.sheet(this, host != null ? host.label() : adHocHost,
                new String[]{"最小化", "复制全部输出", "清屏", "终端字号", "重新连接", "断开连接"},
                new String[]{"minimize", "copy", "trash", "gear", "refresh", "power"}, 5, idx -> {
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
                            term.clearScreen();
                            connect();
                            break;
                        default:
                            askDisconnect();
                            break;
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
        if (session == null || !session.isConnected()) {
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

    /** 把保存的片段直接发到当前会话 */
    private void snippetSheet() {
        if (term != null) term.clearCtrlLatch();
        List<Snippet> list = store.snippets();
        if (list.isEmpty()) {
            Dialogs.alert(this, "还没有代码片段",
                    "先到主机的「代码片段」里把常用命令存起来，这里就能一键发送。");
            return;
        }
        String[] labels = new String[list.size()];
        String[] subs = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Snippet sn = list.get(i);
            labels[i] = sn.name;
            subs[i] = sn.command.replace("\n", " ⏎ ");
        }
        Dialogs.choose(this, "发送代码片段", labels, subs, -1, idx -> {
            Snippet sn = list.get(idx);
            term.scrollToBottom();
            for (String line : sn.command.split("\n")) {
                write(line + "\r");
            }
            UI.toast(this, "已发送：" + sn.name);
        });
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        String text = term.allText();
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        UI.toast(this, "已复制 " + text.length() + " 个字符");
    }

    private void fontSheet() {
        final int[] sizes = {11, 13, 15, 17, 20};
        String[] labels = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) labels[i] = sizes[i] + " sp";
        Dialogs.sheet(this, "终端字号", labels, null, -1, idx -> {
            term.setFontSizeSp(sizes[idx]);
            store.setFontSize(sizes[idx]);
            term.post(() -> {
                if (session != null && term.buffer() != null) {
                    session.resize(term.buffer().cols, term.buffer().rows);
                }
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
                    finish();
                });
    }

    @Override
    public void onBackPressed() {
        if (session != null && session.isConnected()) askDisconnect();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // 最小化时不走这里；真走到这里就是页面销毁，会话跟着关，
        // 登记也要清掉，主机列表上的悬浮卡片才不会变成死按钮。
        SessionRegistry.clearIf(this);
        if (session != null) session.close();
        super.onDestroy();
    }

    /* ------------------------------------------------------------- 浮层 */

    private void showOverlay(String state, String title, String detail) {
        if (overlay == null) return;
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
