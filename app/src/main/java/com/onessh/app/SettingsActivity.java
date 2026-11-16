package com.onessh.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onessh.app.data.HostIO;
import com.onessh.app.data.SnippetIO;
import com.onessh.app.data.Store;
import com.onessh.app.ui.Dialogs;
import com.onessh.app.ui.UI;
import com.onessh.app.ui.Widgets;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 设置：字号、常亮、已信任的主机密钥、清空数据、关于。 */
public class SettingsActivity extends Activity {

    private static final int REQ_IMPORT_FILE = 201;
    private static final int REQ_EXPORT_FILE = 202;
    private static final int REQ_IMPORT_SNIPPETS = 101;
    private static final int REQ_EXPORT_SNIPPETS = 102;

    private Store store;
    private LinearLayout root;

    /** 安全卡片默认折起来；点标题展开，删主机重建页面时保持展开状态 */
    private boolean securityExpanded;

    /* 导出文件时先把文本攒着，等用户选好位置再写 */
    private String pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        build();
    }

    private void build() {
        root = Widgets.screen(this);
        root.addView(Widgets.bar(this, getString(R.string.settings_title), this::finish, null));

        ScrollView sc = Widgets.scroll(this);
        LinearLayout body = UI.column(this);
        body.setPadding(UI.dp(20), UI.dp(6), UI.dp(20), UI.dp(28));
        sc.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* 终端 */
        LinearLayout term = Widgets.cardBox(this);
        term.addView(Widgets.cardTitle(this, getString(R.string.settings_terminal)));
        term.addView(fontRow());
        term.addView(UI.hline(this, UI.LINE2));
        term.addView(keepAwakeRow());
        body.addView(term);
        Widgets.bottom(term, 14);

        /* 安全：默认折起来，点标题展开；标题右边显示已信任主机数 */
        Map<String, String> known = store.knownHosts();
        LinearLayout sec = Widgets.cardBox(this);
        sec.addView(securityHeader(known.size()));
        if (securityExpanded) {
            if (known.isEmpty()) {
                sec.addView(UI.text(this, getString(R.string.settings_known_empty), 12f, UI.MUTED2));
            } else {
                for (Map.Entry<String, String> e : known.entrySet()) {
                    sec.addView(knownRow(e.getKey(), e.getValue()));
                }
            }
        }
        body.addView(sec);
        Widgets.bottom(sec, 14);

        /* 数据 */
        LinearLayout data = Widgets.cardBox(this);
        data.addView(Widgets.cardTitle(this, "数据"));
        LinearLayout importBtn = Widgets.button(this, "导入主机", "download", false);
        importBtn.setOnClickListener(v -> importSheet());
        data.addView(importBtn);
        Widgets.bottom(importBtn, 8);
        LinearLayout exportBtn = Widgets.button(this, "导出主机", "upload", false);
        exportBtn.setOnClickListener(v -> exportSheet());
        data.addView(exportBtn);
        Widgets.bottom(exportBtn, 8);
        LinearLayout importSnippetBtn = Widgets.button(this, "导入片段", "download", false);
        importSnippetBtn.setOnClickListener(v -> importSnippetSheet());
        data.addView(importSnippetBtn);
        Widgets.bottom(importSnippetBtn, 8);
        LinearLayout exportSnippetBtn = Widgets.button(this, "导出片段", "upload", false);
        exportSnippetBtn.setOnClickListener(v -> exportSnippetSheet());
        data.addView(exportSnippetBtn);
        Widgets.bottom(exportSnippetBtn, 8);
        data.addView(UI.hline(this, UI.LINE2));
        LinearLayout clear = Widgets.button(this, getString(R.string.settings_clear), "trash", false);
        ((TextView) clear.getChildAt(1)).setTextColor(UI.RED);
        clear.setBackground(UI.ripple(UI.bg(0xFFFFF7F6, 10f, 0xFFF0D5D1, 1f), 10f));
        clear.setOnClickListener(v -> Dialogs.confirm(this, getString(R.string.settings_clear),
                getString(R.string.settings_clear_msg), "全部删除", true, () -> {
                    store.clearAll();
                    UI.toast(this, "已清空");
                    build();
                }));
        data.addView(clear);
        body.addView(data);
        Widgets.bottom(data, 14);

        /* 关于 */
        LinearLayout about = Widgets.cardBox(this);
        about.addView(Widgets.cardTitle(this, getString(R.string.settings_about)));
        TextView ver = UI.medium(this, "OneSSH " + BuildConfig.VERSION_NAME, 13f, UI.INK);
        about.addView(ver);
        TextView txt = UI.text(this, getString(R.string.settings_about_body, BuildConfig.VERSION_NAME),
                11.5f, UI.MUTED);
        txt.setLineSpacing(UI.dp(3), 1f);
        Widgets.top(txt, 8);
        about.addView(txt);
        body.addView(about);

        setContentView(root);
    }

    /* ------------------------------------------------------------- 导入 / 导出（主机） */

    private void importSheet() {
        Dialogs.sheet(this, "导入主机",
                new String[]{"从剪贴板导入", "从文件导入"},
                new String[]{"clipboard", "folder"}, -1, idx -> {
                    if (idx == 0) importFromClipboard();
                    else importFromFile();
                });
    }

    private void exportSheet() {
        Dialogs.sheet(this, "导出主机",
                new String[]{"复制到剪贴板", "保存到文件"},
                new String[]{"clipboard", "folder"}, -1, idx -> {
                    if (idx == 0) exportToClipboard();
                    else exportToFile();
                });
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null || text.toString().trim().isEmpty()) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        doImport(text.toString());
    }

    private void importFromFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_FILE);
        } catch (Exception e) {
            UI.toast(this, "没有可用的文件选择器");
        }
    }

    private void doImport(String text) {
        HostIO.ImportData data = HostIO.parse(text);
        if (data.hosts.isEmpty() && data.groups.isEmpty()) {
            UI.toast(this, "没有可导入的主机");
            return;
        }
        HostIO.MergeResult r = HostIO.merge(store, data);
        UI.toast(this, "导入完成：新增 " + r.hostsAdded + " 台主机、"
                + r.groupsAdded + " 个分组"
                + (r.hostsSkipped > 0 ? "，跳过 " + r.hostsSkipped + " 台重复" : ""));
        // 主机列表在 MainActivity 里，返回时 onResume 会重新渲染，这里不用刷
    }

    private void exportToClipboard() {
        String text = HostIO.exportText(store);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("hosts", text));
        UI.toast(this, "已复制到剪贴板");
    }

    private void exportToFile() {
        pendingExport = HostIO.exportText(store);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, HostIO.exportFileName());
        try {
            startActivityForResult(i, REQ_EXPORT_FILE);
        } catch (Exception e) {
            UI.toast(this, "没有可用的文件选择器");
        }
    }

    /* ------------------------------------------------------------- 导入 / 导出（片段） */

    private void importSnippetSheet() {
        Dialogs.sheet(this, "导入片段",
                new String[]{"从剪贴板导入", "从文件导入"},
                new String[]{"clipboard", "folder"}, -1, idx -> {
                    if (idx == 0) importSnippetFromClipboard();
                    else importSnippetFromFile();
                });
    }

    private void exportSnippetSheet() {
        Dialogs.sheet(this, "导出片段",
                new String[]{"复制到剪贴板", "保存到文件"},
                new String[]{"clipboard", "folder"}, -1, idx -> {
                    if (idx == 0) exportSnippetToClipboard();
                    else exportSnippetToFile();
                });
    }

    private void importSnippetFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null || text.toString().trim().isEmpty()) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        doImportSnippet(text.toString());
    }

    private void importSnippetFromFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_SNIPPETS);
        } catch (Exception e) {
            UI.toast(this, "没有可用的文件选择器");
        }
    }

    private void doImportSnippet(String text) {
        SnippetIO.ImportData data;
        try {
            data = SnippetIO.parse(text);
        } catch (Exception e) {
            UI.toast(this, "导入失败：不是有效的片段导出文件");
            return;
        }
        if (data.snippets.isEmpty() && data.groups.isEmpty()) {
            UI.toast(this, "没有可导入的片段");
            return;
        }
        SnippetIO.MergeResult r = SnippetIO.merge(store, data);
        UI.toast(this, "导入完成：新增 " + r.snippetsAdded + " 条片段、"
                + r.groupsAdded + " 个分组"
                + (r.snippetsSkipped > 0 ? "，跳过 " + r.snippetsSkipped + " 条重复" : ""));
        // 片段列表在 SnippetsActivity 里，返回时 onResume 会重新渲染，这里不用刷
    }

    private void exportSnippetToClipboard() {
        String json = SnippetIO.exportJson(store.snippetGroups(), store.snippets());
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("snippets", json));
        UI.toast(this, "已复制到剪贴板");
    }

    private void exportSnippetToFile() {
        pendingExport = SnippetIO.exportJson(store.snippetGroups(), store.snippets());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, SnippetIO.exportFileName());
        try {
            startActivityForResult(i, REQ_EXPORT_SNIPPETS);
        } catch (Exception e) {
            UI.toast(this, "没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_FILE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("读不到文件");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                doImport(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                UI.toast(this, "导入失败：" + e.getMessage());
            }
        } else if (requestCode == REQ_EXPORT_FILE) {
            if (pendingExport == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("写不到文件");
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                out.flush();
                UI.toast(this, "已导出");
            } catch (Exception e) {
                UI.toast(this, "导出失败：" + e.getMessage());
            } finally {
                pendingExport = null;
            }
        } else if (requestCode == REQ_IMPORT_SNIPPETS) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("读不到文件");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                doImportSnippet(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                UI.toast(this, "导入失败：" + e.getMessage());
            }
        } else if (requestCode == REQ_EXPORT_SNIPPETS) {
            if (pendingExport == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("写不到文件");
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                out.flush();
                UI.toast(this, "已导出");
            } catch (Exception e) {
                UI.toast(this, "导出失败：" + e.getMessage());
            } finally {
                pendingExport = null;
            }
        }
    }

    private View fontRow() {
        LinearLayout row = UI.row(this);
        UI.padding(row, 0, 10, 0, 10);

        LinearLayout col = UI.column(this);
        col.addView(UI.text(this, getString(R.string.settings_font), 13.5f, UI.INK));
        col.addView(UI.text(this, "终端里等宽字体的字号", 11f, UI.MUTED2));
        row.addView(col);
        UI.weight(col, 1f);

        row.addView(stepButton("-", () -> {
            store.setFontSize(store.fontSize() - 1);
            build();
        }));

        TextView value = UI.mono(this, store.fontSize() + " sp", 13f, UI.INK);
        value.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(UI.dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        value.setLayoutParams(vp);
        row.addView(value);

        row.addView(stepButton("+", () -> {
            store.setFontSize(store.fontSize() + 1);
            build();
        }));
        return row;
    }

    private View stepButton(String label, Runnable action) {
        TextView t = UI.medium(this, label, 16f, UI.INK);
        t.setGravity(Gravity.CENTER);
        t.setBackground(UI.ripple(UI.bg(UI.PAPER2, 8f, UI.LINE, 1f), 8f));
        t.setLayoutParams(new LinearLayout.LayoutParams(UI.dp(34), UI.dp(30)));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private View keepAwakeRow() {
        LinearLayout row = UI.row(this);
        UI.padding(row, 0, 12, 0, 4);

        LinearLayout col = UI.column(this);
        col.addView(UI.text(this, getString(R.string.settings_keepawake), 13.5f, UI.INK));
        col.addView(UI.text(this, "连着的终端页不会自动息屏", 11f, UI.MUTED2));
        row.addView(col);
        UI.weight(col, 1f);

        Widgets.Toggle sw = new Widgets.Toggle(this, store.keepAwake());
        sw.setOnChange(store::setKeepAwake);
        row.addView(sw);
        return row;
    }

    /** 安全卡片的标题行：跟首页分组一样的折叠样式——chevron + 标题，数量放最右边 */
    private View securityHeader(int count) {
        LinearLayout head = UI.row(this);
        head.setBackground(UI.ripple(0x00000000, 8f));
        head.setPadding(0, 0, 0, UI.dp(12));

        ImageView chev = UI.icon(this, "chevron", UI.MUTED, 12);
        chev.setRotation(securityExpanded ? 90f : 0f);
        head.addView(chev);

        TextView title = UI.medium(this, getString(R.string.settings_security), 12f, UI.MUTED);
        title.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = UI.dp(7);
        title.setLayoutParams(tp);
        head.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(spacer);

        head.addView(UI.text(this, count + " 台", 11f, UI.MUTED2));

        head.setOnClickListener(v -> {
            securityExpanded = !securityExpanded;
            build();
        });
        return head;
    }

    private View knownRow(String hostPort, String fingerprint) {
        LinearLayout row = UI.row(this);
        row.setBackground(UI.ripple(0x00000000, 8f));
        UI.padding(row, 0, 9, 0, 9);

        LinearLayout col = UI.column(this);
        col.addView(UI.medium(this, hostPort, 12.5f, UI.INK2));
        TextView fp = UI.mono(this, fingerprint, 10f, UI.MUTED);
        fp.setSingleLine(true);
        fp.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Widgets.top(fp, 3);
        col.addView(fp);
        row.addView(col);
        UI.weight(col, 1f);

        row.addView(Widgets.barIcon(this, "trash", UI.MUTED2, () ->
                Dialogs.confirm(this, "忘记这台主机",
                        "删除后下次连接 " + hostPort + " 会重新询问指纹。",
                        "删除", true, () -> {
                            store.forgetHost(hostPort);
                            build();
                        })));

        row.setOnClickListener(v -> Dialogs.alert(this, hostPort, fingerprint));
        return row;
    }
}
