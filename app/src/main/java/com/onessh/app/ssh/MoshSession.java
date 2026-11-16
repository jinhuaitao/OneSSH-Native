package com.onessh.app.ssh;

import android.content.Context;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import com.onessh.app.ssh.PtyBridge.Started;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条 Mosh 会话：SSH 引导 + UDP 子进程。
 *
 * 流程（调研报告 §5 的路线 a′）：
 *   1. 复用现有 JSch 配置连上 SSH（密码 / 公钥 / keyboard-interactive，
 *      主机密钥同样走 TOFU），在服务器上跑
 *      {@code mosh-server new -s -c 256 -l en_US.UTF-8}，解析输出里的
 *      {@code MOSH CONNECT <端口> <密钥>}，然后关掉 SSH；
 *   2. 把密钥放进 MOSH_KEY 环境变量，在一个已设好真实 winsize 的 pty 上
 *      启动打包进 APK 的 mosh-client 子进程（libmoshclientbin.so）；
 *   3. pty 主端的输出字节直接喂给现有 TerminalBuffer，用户输入写回主端；
 *      窗口大小变化时同步 winsize（mosh-client 靠 SIGWINCH 感知）。
 *
 * Mosh 没有 scrollback、没有端口转发/文件传输，这些仍走 SSH/SFTP；
 * 跳板机暂不支持（引导要直接在目标主机上跑 exec）。
 * mosh-server 的残留回收（断开后服务器上多余的 mosh-server）是后续步骤，
 * 这里只做到正常断开时杀掉本地子进程。
 */
public final class MoshSession {

    /** mosh-server 的输出里找这一行：MOSH CONNECT <端口> <密钥> */
    private static final Pattern MOSH_CONNECT =
            Pattern.compile("MOSH CONNECT\\s+(\\d+)\\s+(\\S+)");

    /** mosh-server 的启动命令：-s 打印到 stdout，-c 256 颜色数，-l 指定 locale */
    private static final String MOSH_SERVER_CMD =
            "mosh-server new -s -c 256 -l en_US.UTF-8";

    /** 引导 exec 最多等 15 秒 */
    private static final long BOOTSTRAP_TIMEOUT_MS = 15000;
    /** 子进程退出后先 SIGTERM，等 800ms 还不死再 SIGKILL */
    private static final long KILL_GRACE_MS = 800;

    private final Context appContext;
    private final SshSession.Config cfg;
    private final SshSession.Listener listener;
    private final SshSession.HostKeyVerifier verifier;
    private final String hostPort;

    private volatile boolean closed;
    private volatile boolean childExited;
    private volatile int childExitCode = -1;

    private Session bootstrapSession;
    private int ptyFd = -1;
    private int childPid = -1;
    private Thread reader;

    public MoshSession(Context context, SshSession.Config cfg,
                       SshSession.Listener listener,
                       SshSession.HostKeyVerifier verifier) {
        this.appContext = context.getApplicationContext();
        this.cfg = cfg;
        this.listener = listener;
        this.verifier = verifier;
        this.hostPort = SshSession.hostPort(cfg.host, cfg.port);
    }

    /* ------------------------------------------------------------- 连接 */

    public void connect() {
        listener.onState("connecting", null);
        try {
            if (cfg.jump != null) {
                listener.onState("error", "Mosh 暂不支持跳板机：请先在主机设置里去掉跳板机，"
                        + "或关掉“使用 Mosh”走普通 SSH");
                return;
            }

            listener.onState("connecting", "正在用 SSH 启动 mosh-server…");
            String[] connect = bootstrap();
            if (connect == null) return; // 出错已经报过 onState("error")

            int port = Integer.parseInt(connect[0]);
            String key = connect[1];

            listener.onState("connecting", "正在启动 mosh-client…");
            if (!startClient(cfg.host, port, key)) return;

            listener.onState("connected", null);

            reader = new Thread(this::pump, "mosh-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (JSchException e) {
            listener.onState("error", friendly(e));
            close();
        } catch (Exception e) {
            String m = e.getMessage();
            listener.onState("error", m == null || m.isEmpty() ? "连接失败" : m);
            close();
        }
    }

    /**
     * SSH 引导：连上 SSH，跑 mosh-server，解析 MOSH CONNECT。
     * 成功返回 {端口, 密钥}；失败时自己报 onState("error") 并返回 null。
     */
    private String[] bootstrap() throws JSchException {
        JSch jsch = new JSch();
        if (cfg.privateKey != null && !cfg.privateKey.trim().isEmpty()) {
            byte[] pass = cfg.passphrase == null || cfg.passphrase.isEmpty()
                    ? null : cfg.passphrase.getBytes(StandardCharsets.UTF_8);
            jsch.addIdentity("hostkey", cfg.privateKey.getBytes(StandardCharsets.UTF_8), null, pass);
        }
        jsch.setHostKeyRepository(new StoreHostKeys(hostPort));

        bootstrapSession = jsch.getSession(cfg.user, cfg.host, cfg.port);
        bootstrapSession.setConfig(buildConfig());
        bootstrapSession.setUserInfo(new Prompt());
        if (cfg.password != null) bootstrapSession.setPassword(cfg.password);
        bootstrapSession.setServerAliveInterval(30000);
        bootstrapSession.setServerAliveCountMax(3);
        bootstrapSession.connect(20000);

        ChannelExec ex = null;
        try {
            ex = (ChannelExec) bootstrapSession.openChannel("exec");
            ex.setCommand(MOSH_SERVER_CMD);
            InputStream in = ex.getInputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ex.setErrStream(err);
            ex.connect(5000);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                int avail = in.available();
                if (avail > 0) {
                    int r = in.read(buf, 0, Math.min(avail, buf.length));
                    if (r > 0) out.write(buf, 0, r);
                } else if (ex.isClosed()) {
                    break;
                } else {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            // 再把残留的输出读干净
            while (in.available() > 0) {
                int r = in.read(buf, 0, Math.min(in.available(), buf.length));
                if (r <= 0) break;
                out.write(buf, 0, r);
            }

            String stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
            String stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
            String combined = stdout + "\n" + stderr;

            Matcher m = MOSH_CONNECT.matcher(combined);
            if (m.find()) {
                return new String[]{m.group(1), m.group(2)};
            }

            listener.onState("error", classifyBootstrapFailure(combined, ex.getExitStatus()));
            return null;
        } catch (JSchException e) {
            throw e;
        } catch (Exception e) {
            listener.onState("error", "启动 mosh-server 时出错：" + e.getMessage());
            return null;
        } finally {
            if (ex != null) {
                try {
                    ex.disconnect();
                } catch (Exception ignored) {
                }
            }
            // 引导做完就关 SSH：之后只走 mosh-client 到 mosh-server 的 UDP
            Session s = bootstrapSession;
            bootstrapSession = null;
            if (s != null) {
                try {
                    s.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 输出里有没有 MOSH CONNECT 行（mosh-server 成功启动的标志） */
    public static boolean containsMoshConnect(String output) {
        return output != null && MOSH_CONNECT.matcher(output).find();
    }

    /** 引导失败分三类，别笼统报“连接失败” */
    public static String classifyBootstrapFailure(String output, int exitStatus) {
        String o = output == null ? "" : output;
        String lower = o.toLowerCase();
        if (lower.contains("command not found") || lower.contains("not found")
                || lower.contains("no such file") || exitStatus == 127) {
            return "服务器没装 mosh-server：先在服务器上安装，"
                    + "如 Debian/Ubuntu 用 sudo apt install mosh，装完再试";
        }
        if (lower.contains("utf-8") || lower.contains("utf8") || lower.contains("locale")) {
            return "服务器 locale 不是 UTF-8，mosh-server 拒绝启动："
                    + "请在服务器上把 LANG 设成 en_US.UTF-8 这类 UTF-8 locale 再试";
        }
        String first = o.trim().split("\n")[0].trim();
        if (!first.isEmpty()) {
            return "mosh-server 启动失败：" + first;
        }
        return "mosh-server 没有返回 MOSH CONNECT，请检查服务器上的 mosh 是否可用";
    }

    /** 在 pty 上启动 mosh-client 子进程。失败自己报 onState("error")。 */
    private boolean startClient(String host, int port, String key) {
        String bin = new File(appContext.getApplicationInfo().nativeLibraryDir,
                "libmoshclientbin.so").getAbsolutePath();
        File f = new File(bin);
        if (!f.exists() || !f.canExecute()) {
            listener.onState("error", "找不到内置 mosh-client：当前只带了 arm64-v8a 版本，"
                    + "这台设备可能不是 arm64");
            return false;
        }

        Started s = PtyBridge.start(bin,
                new String[]{host, String.valueOf(port)},
                new String[]{
                        "MOSH_KEY=" + key,
                        "TERM=xterm-256color",
                        "LANG=en_US.UTF-8",
                        "LC_ALL=en_US.UTF-8",
                },
                cfg.cols, cfg.rows);
        if (s == null) {
            listener.onState("error", "pty 分配失败，mosh-client 没能启动");
            return false;
        }
        ptyFd = s.fd;
        childPid = s.pid;
        return true;
    }

    /** pty 读线程：主端输出直接喂终端；子进程一死就收尾 */
    private void pump() {
        byte[] buf = new byte[16384];
        long startedAt = System.currentTimeMillis();
        boolean sawOutput = false;
        int exitCode = -1; // -1：还不知道（子进程可能还在跑）
        try {
            while (!closed) {
                int n = PtyBridge.read(ptyFd, buf, 0, buf.length);
                if (n > 0) {
                    sawOutput = true;
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    listener.onOutput(chunk);
                } else if (n < 0) {
                    break; // EOF：对端关了，循环外再查一次退出码
                } else {
                    // 250ms 没数据：顺便看一眼子进程还在不在
                    int w = PtyBridge.waitPid(childPid);
                    if (w != -1) {
                        exitCode = w;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // waitpid 收过一次尸就再查不到（返回 -2），所以只在还没拿到时查
        if (exitCode < 0) exitCode = PtyBridge.waitPid(childPid);
        if (exitCode >= 0) {
            childExited = true;
            childExitCode = exitCode;
        }
        if (closed) return;

        if (exitCode >= 0 && exitCode != 0) {
            String msg = "mosh-client 已退出（退出码 " + exitCode + "）";
            if (!sawOutput || System.currentTimeMillis() - startedAt < 5000) {
                msg += "。若刚连上就退出，最可能是 UDP 不通："
                        + "检查服务器是否放行 UDP 60000–61000、地址填得对不对，"
                        + "或在服务器上看 mosh-server 是否还在跑";
            }
            listener.onState("error", msg);
        } else {
            listener.onState("closed", null);
        }
    }

    /* ------------------------------------------------------------- 输入 */

    /** 用户输入写回 pty 主端 */
    public void write(byte[] data) {
        if (data == null || data.length == 0 || closed || ptyFd < 0) return;
        PtyBridge.write(ptyFd, data, 0, data.length);
    }

    /** 窗口大小变化：同步 winsize，mosh-client 靠 SIGWINCH 感知 */
    public void resize(int cols, int rows) {
        if (cols <= 0 || rows <= 0) return;
        cfg.cols = cols;
        cfg.rows = rows;
        if (!closed && ptyFd >= 0) PtyBridge.setWinsize(ptyFd, cols, rows);
    }

    public boolean isConnected() {
        return !closed && !childExited && ptyFd >= 0 && childPid > 0;
    }

    public void close() {
        if (closed) return;
        closed = true;

        final int pid = childPid;
        final int fd = ptyFd;
        childPid = -1;
        ptyFd = -1;

        Session s = bootstrapSession;
        bootstrapSession = null;

        Thread t = new Thread(() -> {
            try {
                if (s != null) s.disconnect();
            } catch (Exception ignored) {
            }
            if (pid > 0) {
                PtyBridge.kill(pid, PtyBridge.SIGTERM);
                long deadline = System.currentTimeMillis() + KILL_GRACE_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (PtyBridge.waitPid(pid) != -1) break;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                if (PtyBridge.waitPid(pid) == -1) {
                    PtyBridge.kill(pid, PtyBridge.SIGKILL);
                }
                PtyBridge.waitPid(pid);
            }
            if (fd >= 0) PtyBridge.close(fd);
        }, "mosh-close");
        t.setDaemon(true);
        t.start();
    }

    /* --------------------------------------------------------- 主机密钥 */

    /** 和 SshSession.StoreHostKeys 同一套 TOFU 逻辑（那是 private，这里复制一份） */
    private final class StoreHostKeys implements HostKeyRepository {

        private final String hostPort;

        StoreHostKeys(String hostPort) {
            this.hostPort = hostPort;
        }

        @Override
        public int check(String host, byte[] key) {
            String fp = SshSession.fingerprint(key);
            String known = KnownHosts.known(hostPort);
            if (known == null || known.isEmpty()) {
                return verifier != null && verifier.verify(hostPort, fp, null)
                        ? OK : NOT_INCLUDED;
            }
            if (known.equals(fp)) return OK;
            return verifier != null && verifier.verify(hostPort, fp, known) ? OK : CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
            KnownHosts.trust(hostPort, SshSession.fingerprintOf(hostkey.getKey()));
        }

        @Override
        public void remove(String host, String type) {
            KnownHosts.forget(hostPort);
        }

        @Override
        public void remove(String host, String type, byte[] key) {
            KnownHosts.forget(hostPort);
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "onessh";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    private final class Prompt implements UserInfo, UIKeyboardInteractive {

        @Override
        public String getPassphrase() {
            return cfg.passphrase;
        }

        @Override
        public String getPassword() {
            return cfg.password;
        }

        @Override
        public boolean promptPassword(String message) {
            return cfg.password != null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return cfg.passphrase != null;
        }

        @Override
        public boolean promptYesNo(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name,
                                                  String instruction, String[] prompt, boolean[] echo) {
            String[] answers = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                answers[i] = (i == 0 && cfg.password != null) ? cfg.password : "";
            }
            return answers;
        }
    }

    /** 和 SshSession.buildConfig 同一套参数（只引导用一次 SSH） */
    private Properties buildConfig() {
        Properties p = new Properties();
        p.put("StrictHostKeyChecking", "yes");
        p.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
        p.put("PubkeyAcceptedAlgorithms",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,"
                        + "rsa-sha2-512,rsa-sha2-256");
        p.put("server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,"
                        + "rsa-sha2-512,rsa-sha2-256");
        p.put("kex", "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,"
                + "ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group16-sha512,"
                + "diffie-hellman-group14-sha256,diffie-hellman-group-exchange-sha256");
        return p;
    }

    /** 引导阶段 SSH 连不上时的提示，和 SshSession.friendly 同一套口径 */
    private static String friendly(JSchException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        String lower = m.toLowerCase();
        if (lower.contains("auth fail") || lower.contains("auth cancel")) return "认证失败：用户名、密码或私钥不对";
        if (lower.contains("timeout") || lower.contains("timed out")) return "连接超时：服务器没响应，检查地址和端口";
        if (lower.contains("connection refused")) return "连接被拒绝：端口没开，或 SSH 服务没运行";
        if (lower.contains("unknownhost") || lower.contains("unknown host")) return "域名解析失败";
        if (lower.contains("network is unreachable")) return "网络不可达";
        if (lower.contains("host key verification failed") || lower.contains("mismatch")) return "主机密钥不匹配，已中止连接";
        if (lower.contains("algorithm negotiation fail")) return "算法协商失败：服务器太老，不支持现代加密算法";
        if (lower.contains("invalid privatekey") || lower.contains("privatekey")) return "私钥无法解析，或需要口令";
        return m.isEmpty() ? "连接失败" : m;
    }
}
