package com.onessh.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onessh.app.data.Host;
import com.onessh.app.data.Store;
import com.onessh.app.ui.Dialogs;
import com.onessh.app.ui.UI;
import com.onessh.app.ui.Widgets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主机总览。左边一条窄导航栏（rail），右边是「我的主机」。
 * 版式取自网页版的 dashboard，只是把三栏压成了适合手机的两栏。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "ui";
    private static final String PREF_COLLAPSED = "collapsedGroups";

    private Store store;

    private LinearLayout railBox;
    private LinearLayout tabsHost;
    private LinearLayout listBox;
    private TextView countText;
    private EditText searchInput;
    /** 最小化会话的悬浮卡片：只在有最小化终端时出现，贴在右侧 */
    private View minimizedCard;
    private TextView minimizedLabel;

    private String query = "";
    private String groupFilter = "";

    private final Set<String> collapsed = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        setTheme(R.style.AppTheme);
        loadCollapsed();

        LinearLayout root = UI.row(this);
        root.setBackgroundColor(UI.PAPER);

        root.addView(buildRail(), new LinearLayout.LayoutParams(UI.dp(60),
                ViewGroup.LayoutParams.MATCH_PARENT));

        View edge = new View(this);
        edge.setBackgroundColor(UI.LINE);
        root.addView(edge, new LinearLayout.LayoutParams(Math.max(1, UI.dp(0.7f)),
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildContent(), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 悬浮卡片盖在内容上面、贴右边缘，不占布局位置
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        minimizedCard = buildMinimizedCard();
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        cp.rightMargin = UI.dp(10);
        frame.addView(minimizedCard, cp);

        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        updateMinimizedCard();
    }

    @Override
    public void onBackPressed() {
        // 有最小化的会话时，返回键不是退出，而是把整个应用退到后台：
        // 否则按返回会先把主机列表弹掉、露出底下的终端，等于白最小化了。
        if (SessionRegistry.hasMinimized()) {
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    /** 右侧悬浮卡片：绿点 + 主机名，点一下回到终端 */
    private View buildMinimizedCard() {
        LinearLayout pill = UI.row(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(UI.bg(UI.TERM_BG, 99f));
        pill.setElevation(UI.dp(6f));
        pill.setPadding(UI.dp(12), UI.dp(10), UI.dp(12), UI.dp(10));

        View dot = new View(this);
        dot.setBackground(UI.bg(UI.GREEN, 99f));
        pill.addView(dot, new LinearLayout.LayoutParams(UI.dp(8), UI.dp(8)));

        minimizedLabel = UI.medium(this, "", 12.5f, UI.TERM_FG);
        minimizedLabel.setSingleLine(true);
        minimizedLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = UI.dp(8);
        lp.rightMargin = UI.dp(8);
        minimizedLabel.setMaxWidth(UI.dp(150));
        minimizedLabel.setLayoutParams(lp);
        pill.addView(minimizedLabel);

        pill.addView(UI.icon(this, "terminal", UI.TERM_FG, 15));
        pill.setOnClickListener(v -> restoreMinimized());
        pill.setVisibility(View.GONE);
        return pill;
    }

    private void updateMinimizedCard() {
        if (minimizedCard == null) return;
        if (SessionRegistry.hasMinimized()) {
            minimizedLabel.setText(SessionRegistry.label());
            minimizedCard.setVisibility(View.VISIBLE);
        } else {
            minimizedCard.setVisibility(View.GONE);
        }
    }

    private void restoreMinimized() {
        Intent i = SessionRegistry.restoreIntent();
        if (i == null) {
            updateMinimizedCard();
            return;
        }
        startActivity(i);
    }

    /* ------------------------------------------------------------- 导航栏 */

    private View buildRail() {
        railBox = UI.column(this);
        railBox.setBackgroundColor(UI.PAPER2);
        railBox.setGravity(Gravity.CENTER_HORIZONTAL);
        railBox.setPadding(0, UI.dp(16), 0, UI.dp(14));

        // 品牌块：橙色圆角方块 + 终端图标
        LinearLayout brand = UI.row(this);
        brand.setGravity(Gravity.CENTER);
        brand.setBackground(UI.bg(UI.ACCENT, 10f));
        brand.setElevation(UI.dp(5f));
        brand.addView(UI.icon(this, "terminal", 0xFFFFFFFF, 19));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(UI.dp(34), UI.dp(34));
        bp.bottomMargin = UI.dp(18);
        railBox.addView(brand, bp);

        railBox.addView(railItem("总览", "home", true, () -> {
        }));
        railBox.addView(railItem("快速", "bolt", false, this::quickConnect));
        railBox.addView(railItem("分组", "folder", false,
                () -> startActivity(new Intent(this, GroupsActivity.class))));
        railBox.addView(railItem("片段", "code", false,
                () -> startActivity(new Intent(this, SnippetsActivity.class))));
        railBox.addView(railItem("凭据", "lock", false,
                () -> startActivity(new Intent(this, VaultActivity.class))));
        railBox.addView(railItem("设置", "gear", false,
                () -> startActivity(new Intent(this, SettingsActivity.class))));

        View spacer = new View(this);
        railBox.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView ver = UI.text(this, "v" + BuildConfig.VERSION_NAME, 8.5f, UI.MUTED2);
        ver.setGravity(Gravity.CENTER);
        railBox.addView(ver);
        return railBox;
    }

    private View railItem(String label, String icon, boolean on, Runnable action) {
        LinearLayout item = UI.column(this);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        if (on) {
            item.setBackground(UI.bg(UI.CARD, 10f));
            item.setElevation(UI.dp(1.5f));
        } else {
            item.setBackground(UI.ripple(0x00000000, 10f));
        }

        ImageView iv = UI.icon(this, icon, on ? UI.ACCENT : UI.MUTED, 19);
        item.addView(iv, new LinearLayout.LayoutParams(UI.dp(19), UI.dp(19)));

        TextView t = UI.text(this, label, 9.5f, on ? UI.ACCENT : UI.MUTED);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = UI.dp(4);
        t.setLayoutParams(tp);
        item.addView(t);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UI.dp(48), UI.dp(48));
        lp.bottomMargin = UI.dp(3);
        item.setLayoutParams(lp);
        item.setOnClickListener(v -> action.run());
        return item;
    }

    /* ------------------------------------------------------------- 内容区 */

    private View buildContent() {
        LinearLayout content = UI.column(this);

        LinearLayout head = UI.column(this);
        head.setPadding(UI.dp(20), UI.dp(20), UI.dp(20), 0);
        head.addView(Widgets.eyebrow(this, getString(R.string.eyebrow_infra)));

        TextView h1 = Widgets.bigTitle(this, getString(R.string.dash_title));
        Widgets.top(h1, 7);
        head.addView(h1);

        TextView sub = Widgets.hint(this, getString(R.string.dash_sub));
        Widgets.top(sub, 8);
        head.addView(sub);
        content.addView(head);

        content.addView(buildSearchRow());

        tabsHost = UI.column(this);
        tabsHost.setPadding(UI.dp(20), UI.dp(16), UI.dp(20), 0);
        content.addView(tabsHost);

        ScrollView sc = Widgets.scroll(this);
        listBox = UI.column(this);
        listBox.setPadding(UI.dp(20), UI.dp(16), UI.dp(20), UI.dp(10));
        sc.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(buildFooter());
        return content;
    }

    private View buildSearchRow() {
        LinearLayout wrap = UI.row(this);
        wrap.setPadding(UI.dp(20), UI.dp(14), UI.dp(20), 0);

        LinearLayout pill = UI.row(this);
        pill.setBackground(UI.bg(UI.CARD, 99f, UI.LINE, 1f));
        pill.setPadding(UI.dp(13), 0, UI.dp(12), 0);
        pill.setMinimumHeight(UI.dp(38));
        pill.addView(UI.icon(this, "search", UI.MUTED, 15));

        searchInput = new EditText(this);
        searchInput.setBackground(null);
        searchInput.setHint(R.string.search_hint);
        searchInput.setHintTextColor(UI.MUTED2);
        searchInput.setTextColor(UI.INK);
        searchInput.setTextSize(13f);
        searchInput.setTypeface(UI.SANS);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setSingleLine(true);
        searchInput.setIncludeFontPadding(false);
        searchInput.setPadding(UI.dp(8), UI.dp(9), UI.dp(4), UI.dp(9));
        searchInput.setText(query);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                renderList();
            }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.leftMargin = UI.dp(6);
        searchInput.setLayoutParams(ip);
        pill.addView(searchInput);

        wrap.addView(pill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout add = UI.row(this);
        add.setGravity(Gravity.CENTER);
        add.setBackground(UI.ripple(UI.ACCENT, 10f));
        add.setElevation(UI.dp(4f));
        add.addView(UI.icon(this, "plus", 0xFFFFFFFF, 17));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(UI.dp(38), UI.dp(38));
        ap.leftMargin = UI.dp(8);
        add.setLayoutParams(ap);
        add.setOnClickListener(v -> startActivity(new Intent(this, HostEditActivity.class)));
        wrap.addView(add);

        return wrap;
    }

    private View buildFooter() {
        LinearLayout foot = UI.row(this);
        foot.setPadding(UI.dp(20), UI.dp(10), UI.dp(20), UI.dp(12));

        countText = UI.text(this, "", 11.5f, UI.MUTED);
        foot.addView(countText);
        UI.weight(countText, 1f);

        LinearLayout refresh = Widgets.link(this, "刷新", "refresh", UI.MUTED);
        refresh.setOnClickListener(v -> {
            render();
            UI.toast(this, "已刷新");
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = UI.dp(14);
        refresh.setLayoutParams(rlp);
        foot.addView(refresh);

        LinearLayout wrap = UI.column(this);
        wrap.addView(UI.hline(this, UI.LINE));
        wrap.addView(foot);
        return wrap;
    }

    /* ------------------------------------------------------------- 渲染 */

    private void render() {
        renderTabs();
        renderList();
    }

    private void renderTabs() {
        tabsHost.removeAllViews();

        List<Host> all = store.hosts();
        List<String> groups = store.groups();

        List<String> labels = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        labels.add("全部");
        counts.add(String.valueOf(all.size()));
        for (String g : groups) {
            int n = 0;
            for (Host h : all) if (g.equals(h.group)) n++;
            labels.add(g);
            counts.add(String.valueOf(n));
        }

        int selected = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).equals(groupFilter)) selected = i + 1;
        }
        if (selected == 0) groupFilter = "";

        tabsHost.addView(Widgets.tabs(this, labels, counts, selected, idx -> {
            groupFilter = idx == 0 ? "" : groups.get(idx - 1);
            render();
        }));
    }

    private List<Host> visible() {
        String q = query.trim().toLowerCase();
        List<Host> out = new ArrayList<>();
        for (Host h : store.hosts()) {
            if (!groupFilter.isEmpty() && !groupFilter.equals(h.group)) continue;
            if (q.isEmpty()) {
                out.add(h);
                continue;
            }
            String hay = (h.name + " " + h.host + " " + h.user + " " + h.group + " "
                    + h.note + " " + h.port).toLowerCase();
            if (hay.contains(q)) out.add(h);
        }
        return out;
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();

        List<Host> list = visible();
        int total = store.hosts().size();

        if (list.isEmpty()) {
            if (total == 0) {
                listBox.addView(Widgets.empty(this, "server", "还没有添加任何主机",
                        "把常用的服务器存进来，之后一键就能连上。\n密码和私钥会用系统密钥库加密后保存在本机。",
                        "添加第一台主机",
                        () -> startActivity(new Intent(this, HostEditActivity.class))));
            } else if (!query.trim().isEmpty()) {
                listBox.addView(Widgets.empty(this, null, "没有匹配的主机",
                        "没有名称、地址、用户名或备注包含「" + query.trim() + "」的主机。",
                        "清空搜索", () -> {
                            query = "";
                            if (searchInput != null) searchInput.setText("");
                            renderList();
                        }));
            } else {
                listBox.addView(Widgets.empty(this, null, "这个分组还没有主机",
                        "换个筛选条件试试。", "查看全部", () -> {
                            groupFilter = "";
                            render();
                        }));
            }
        } else {
            Map<String, List<Host>> groups = new LinkedHashMap<>();
            for (Host h : list) {
                String g = h.group == null ? "" : h.group.trim();
                if (g.isEmpty()) g = getString(R.string.ungrouped);
                List<Host> bucket = groups.get(g);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    groups.put(g, bucket);
                }
                bucket.add(h);
            }

            List<String> names = new ArrayList<>(groups.keySet());
            java.util.Collections.sort(names, (a, b) -> {
                String un = getString(R.string.ungrouped);
                if (a.equals(un)) return 1;
                if (b.equals(un)) return -1;
                return a.compareToIgnoreCase(b);
            });

            for (String name : names) {
                listBox.addView(buildGroup(name, groups.get(name)));
            }
        }

        if (countText != null) {
            int regions = store.groups().size();
            String text;
            if (total == 0) {
                text = "还没有主机";
            } else if (list.size() != total) {
                text = list.size() + " / " + total + " 台主机 · " + regions + " 个分组";
            } else {
                text = total + " 台主机 · " + regions + " 个分组";
            }
            countText.setText(text);
        }
    }

    private View buildGroup(String name, List<Host> hosts) {
        LinearLayout group = UI.column(this);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.bottomMargin = UI.dp(14);
        group.setLayoutParams(gp);

        boolean isCollapsed = collapsed.contains(name);

        LinearLayout head = UI.row(this);
        head.setBackground(UI.ripple(0x00000000, 8f));
        head.setPadding(0, UI.dp(4), 0, UI.dp(8));

        ImageView chev = UI.icon(this, "chevron", UI.MUTED, 12);
        chev.setRotation(isCollapsed ? 0f : 90f);
        head.addView(chev);

        TextView b = UI.medium(this, name, 12f, UI.INK2);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = UI.dp(7);
        b.setLayoutParams(bp);
        head.addView(b);

        TextView c = UI.text(this, hosts.size() + " 台", 11f, UI.MUTED2);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = UI.dp(8);
        c.setLayoutParams(cp);
        head.addView(c);

        head.setOnClickListener(v -> {
            if (collapsed.contains(name)) collapsed.remove(name);
            else collapsed.add(name);
            saveCollapsed();
            renderList();
        });
        group.addView(head);

        if (!isCollapsed) {
            for (Host h : hosts) group.addView(buildHostRow(h));
        }
        return group;
    }

    private View buildHostRow(Host h) {
        LinearLayout row = UI.row(this);
        row.setBackground(UI.ripple(UI.bg(UI.CARD, 12f, UI.LINE, 1f), 12f));
        row.setElevation(UI.dp(1f));
        UI.padding(row, 12, 11, 6, 11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UI.dp(8);
        row.setLayoutParams(lp);

        String seed = h.name == null || h.name.trim().isEmpty() ? h.host : h.name;
        row.addView(Widgets.avatar(this, Widgets.initials(seed), Widgets.autoColor(seed), 38f, 15f));

        LinearLayout main = UI.column(this);
        // 没起名字就直接显示地址，避免标题和副标题一模一样
        String title = h.name == null || h.name.trim().isEmpty() ? h.host : h.name.trim();
        TextView name = UI.medium(this, title, 13.5f, UI.INK);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        main.addView(name);

        String subText = h.subtitle();
        if (h.jumpId != null && !h.jumpId.isEmpty()) {
            Host jump = store.host(h.jumpId);
            if (jump != null) subText = subText + " · 经 " + jump.label();
        }
        TextView sub = UI.mono(this, subText, 11.5f, UI.MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Widgets.top(sub, 3);
        main.addView(sub);

        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = UI.dp(13);
        row.addView(main, mp);

        View more = Widgets.barIcon(this, "more", UI.MUTED, () -> hostMenu(h));
        row.addView(more);

        row.setOnClickListener(v -> connect(h));
        row.setOnLongClickListener(v -> {
            hostMenu(h);
            return true;
        });
        return row;
    }

    private void hostMenu(Host h) {
        Dialogs.sheet(this, h.label(),
                new String[]{"连接", "编辑", "复制地址", "删除"},
                new String[]{"terminal", "edit", "copy", "trash"}, 3, idx -> {
                    switch (idx) {
                        case 0:
                            connect(h);
                            break;
                        case 1:
                            Intent i = new Intent(this, HostEditActivity.class);
                            i.putExtra(HostEditActivity.EXTRA_ID, h.id);
                            startActivity(i);
                            break;
                        case 2:
                            ClipboardManager cm =
                                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("ssh", h.subtitle()));
                                UI.toast(this, "已复制 " + h.subtitle());
                            }
                            break;
                        default:
                            Dialogs.confirm(this, getString(R.string.delete_host_title),
                                    getString(R.string.delete_host_msg, h.label()),
                                    "删除", true, () -> {
                                        store.deleteHost(h.id);
                                        render();
                                    });
                            break;
                    }
                });
    }

    private void connect(Host h) {
        if (SessionRegistry.hasMinimized()) {
            // 点的正是最小化那个主机：直接回终端，别再开一个新会话
            if (SessionRegistry.label().equals(h.label())) {
                restoreMinimized();
                return;
            }
            // 点的是别的主机：先关掉旧的最小化会话（只允许一个会话），再连新的
            TerminalActivity old = SessionRegistry.activity();
            if (old != null) old.finishMinimized();
        }
        store.touchHost(h.id);
        Intent i = new Intent(this, TerminalActivity.class);
        i.putExtra(TerminalActivity.EXTRA_HOST_ID, h.id);
        startActivity(i);
    }

    /* --------------------------------------------------------- 快速连接 */

    private void quickConnect() {
        LinearLayout form = UI.column(this);

        Widgets.Field host = Widgets.field(this, "地址", "域名 / IPv4 / IPv6", InputType.TYPE_CLASS_TEXT);
        form.addView(host.root);

        LinearLayout row = UI.row(this);
        row.setGravity(Gravity.BOTTOM);
        Widgets.Field user = Widgets.field(this, "用户名", "root", InputType.TYPE_CLASS_TEXT);
        row.addView(user.root, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Widgets.Field port = Widgets.field(this, "端口", "22", InputType.TYPE_CLASS_NUMBER);
        port.setText("22");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(UI.dp(76),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.leftMargin = UI.dp(10);
        row.addView(port.root, pp);
        Widgets.top(row, 12);
        form.addView(row);

        Widgets.Field pass = Widgets.field(this, "密码",
                "留空则尝试无密码 / 私钥",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Widgets.top(pass.root, 12);
        form.addView(pass.root);

        Dialogs.custom(this, "快速连接", "不保存，连完就走。", form, "连接", "取消", false, () -> {
            String addr = host.text().trim();
            if (addr.isEmpty()) {
                UI.toast(this, "地址不能为空");
                return false;
            }
            int p = 22;
            try {
                p = Integer.parseInt(port.text().trim());
            } catch (Exception ignored) {
            }
            // 快速连接是另一台主机：先关掉最小化的旧会话，再连新的
            if (SessionRegistry.hasMinimized()) {
                TerminalActivity old = SessionRegistry.activity();
                if (old != null) old.finishMinimized();
            }
            Intent i = new Intent(this, TerminalActivity.class);
            i.putExtra(TerminalActivity.EXTRA_HOST, addr);
            i.putExtra(TerminalActivity.EXTRA_PORT, p);
            i.putExtra(TerminalActivity.EXTRA_USER, user.text().trim());
            i.putExtra(TerminalActivity.EXTRA_PASSWORD, pass.text());
            startActivity(i);
            return true;
        });
    }

    /* --------------------------------------------------------- 折叠记忆 */

    private void loadCollapsed() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_COLLAPSED, "");
        collapsed.clear();
        if (raw == null || raw.isEmpty()) return;
        for (String s : raw.split("\u0001")) if (!s.isEmpty()) collapsed.add(s);
    }

    private void saveCollapsed() {
        StringBuilder sb = new StringBuilder();
        for (String s : collapsed) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(s);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_COLLAPSED, sb.toString()).apply();
    }
}
