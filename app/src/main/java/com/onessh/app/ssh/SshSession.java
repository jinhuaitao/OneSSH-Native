package com.onessh.app.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;

/**
 * 一条 SSH 连接：密码 / 公钥 / keyboard-interactive 认证，PTY shell，外加一条 SFTP 通道。
 *
 * 主机密钥走 TOFU：第一次连问一次并记住，之后指纹变了直接拒绝 —— 和网页版的行为一致。
 */
public final class SshSession {

    public interface Listener {
        void onOutput(byte[] data);

        /** state: connecting / connected / closed / error */
        void onState(String state, String message);
    }

    public interface HostKeyVerifier {
        /**
         * 在 SSH 线程上同步调用，实现方可以弹窗并阻塞等待用户选择。
         *
         * @param knownFingerprint 之前记录的指纹，首次连接为 null
         * @return true 表示信任并继续
         */
        boolean verify(String hostPort, String fingerprint, String knownFingerprint);
    }

    public static final class Config {
        public String host;
        public int port = 22;
        public String user;
        public String password;
        public String privateKey;
        public String passphrase;
        public int cols = 80;
        public int rows = 24;
        /** 跳板机配置；为 null 表示直连 */
        public Config jump;

        public String hostPort() {
            return SshSession.hostPort(host, port);
        }
    }

    /** 从保存的主机生成连接参数（含跳板机） */
    public static Config fromHost(com.onessh.app.data.Store store,
                                  com.onessh.app.data.Host h) {
        Config c = fill(store, h);
        if (h.jumpId != null && !h.jumpId.isEmpty() && !h.jumpId.equals(h.id)) {
            com.onessh.app.data.Host j = store.host(h.jumpId);
            if (j != null) c.jump = fill(store, j);
        }
        return c;
    }

    private static Config fill(com.onessh.app.data.Store store,
                               com.onessh.app.data.Host h) {
        Config c = new Config();
        c.host = h.host;
        c.port = h.port <= 0 ? 22 : h.port;
        c.user = store.usernameFor(h);
        c.password = store.passwordFor(h);
        c.privateKey = store.privateKeyFor(h);
        c.passphrase = store.passphraseFor(h);
        return c;
    }

    private final Config cfg;
    private final Listener listener;
    private final HostKeyVerifier verifier;
    private final String hostPort;

    /** 跳板机的转发方式：优先用 SSH 的 direct-tcpip，被拒就退回在跳板机上跑 nc */
    private enum JumpMode { DIRECT, EXEC }

    private JumpMode jumpMode = JumpMode.DIRECT;
    private String jumpCommand;
    private String jumpTool;

    private JSch jsch;
    private JSch jumpJsch;
    private Session session;
    private Session jumpSession;
    private ChannelShell shell;
    private OutputStream out;
    private Thread reader;
    private Thread writer;
    private volatile boolean closed;

    /**
     * 所有要发往远端的数据都先进这个队列，由 writer 线程统一写出去。
     * 键盘输入、终端应答（DA/DSR）、窗口大小变更全在主线程上产生，
     * 直接在主线程 write 会撞 NetworkOnMainThreadException。
     */
    private final java.util.concurrent.LinkedBlockingQueue<Object> outQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();

    public SshSession(Config cfg, Listener listener, HostKeyVerifier verifier) {
        this.cfg = cfg;
        this.listener = listener;
        this.verifier = verifier;
        this.hostPort = hostPort(cfg.host, cfg.port);
    }

    public static String hostPort(String host, int port) {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return port == 22 ? h : h + ":" + port;
    }

    /* ------------------------------------------------------------- 连接 */

    public void connect() {
        listener.onState("connecting", null);
        try {
            if (cfg.jump != null) {
                listener.onState("connecting", "正在连接跳板机…");
                connectJump();
            }

            jsch = new JSch();
            if (cfg.privateKey != null && !cfg.privateKey.trim().isEmpty()) {
                byte[] pass = cfg.passphrase == null || cfg.passphrase.isEmpty()
                        ? null : cfg.passphrase.getBytes(StandardCharsets.UTF_8);
                jsch.addIdentity("hostkey", cfg.privateKey.getBytes(StandardCharsets.UTF_8), null, pass);
            }
            jsch.setHostKeyRepository(new StoreHostKeys(hostPort));

            session = jsch.getSession(cfg.user, cfg.host, cfg.port);
            session.setConfig(buildConfig());
            session.setUserInfo(new Prompt());
            if (cfg.password != null) session.setPassword(cfg.password);
            if (jumpSession != null) {
                // 目标主机的 TCP 连接改走跳板机的 direct-tcpip 通道
                session.setSocketFactory(new JumpSocketFactory(jumpSession));
            }
            session.setServerAliveInterval(30000);
            session.setServerAliveCountMax(3);
            session.connect(20000);

            shell = (ChannelShell) session.openChannel("shell");
            shell.setPtyType("xterm-256color", cfg.cols, cfg.rows, cfg.cols * 8, cfg.rows * 16);
            InputStream in = shell.getInputStream();
            out = shell.getOutputStream();
            shell.connect(15000);

            listener.onState("connected", null);

            writer = new Thread(this::pumpOut, "ssh-writer");
            writer.setDaemon(true);
            writer.start();

            reader = new Thread(() -> pump(in), "ssh-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (JSchException e) {
            String msg = friendly(e);
            listener.onState("error", msg);
            close();
        } catch (Exception e) {
            String msg = clean(e.getMessage());
            // 命令隧道被对端关掉，通常是跳板机上的 nc 连不上目标
            if (jumpMode == JumpMode.EXEC && jumpTool != null
                    && (msg.contains("End of IO Stream") || msg.contains("closed by foreign")
                    || msg.contains("Connection reset"))) {
                msg = "跳板机上的 " + jumpTool + " 连不到 " + cfg.host + ":" + cfg.port
                        + "，先在跳板机执行 " + jumpTool + " -vz " + cfg.host + " " + cfg.port + " 看看";
            }
            listener.onState("error", msg);
            close();
        }
    }

    /** 先把跳板机连上，后面目标主机的 socket 从它转发 */
    private void connectJump() throws JSchException {
        Config j = cfg.jump;
        jumpJsch = new JSch();
        if (j.privateKey != null && !j.privateKey.trim().isEmpty()) {
            byte[] pass = j.passphrase == null || j.passphrase.isEmpty()
                    ? null : j.passphrase.getBytes(StandardCharsets.UTF_8);
            jumpJsch.addIdentity("jumpkey", j.privateKey.getBytes(StandardCharsets.UTF_8), null, pass);
        }
        jumpJsch.setHostKeyRepository(new StoreHostKeys(j.hostPort()));

        jumpSession = jumpJsch.getSession(j.user, j.host, j.port);
        jumpSession.setConfig(buildConfig());
        jumpSession.setUserInfo(new Prompt(j));
        if (j.password != null) jumpSession.setPassword(j.password);
        jumpSession.setServerAliveInterval(30000);
        jumpSession.connect(20000);

        prepareTunnel();
    }

    /**
     * 先试 direct-tcpip；服务器不让转发（AllowTcpForwarding no）就退一步：
     * 在跳板机上跑 nc/ncat/socat，把它连出去的那条 TCP 直接当隧道用。
     * 这条路只需要跳板机有 nc，不需要 sshd 开端口转发。
     */
    private void prepareTunnel() throws JSchException {
        if (canDirectTcpip(cfg.host, cfg.port)) {
            jumpMode = JumpMode.DIRECT;
            return;
        }

        listener.onState("connecting", "跳板机不允许端口转发，改用命令隧道…");

        String tool = detectTunnelTool();
        if (tool == null) {
            throw new JSchException("跳板机既不允许端口转发，也没装 nc / ncat / socat。"
                    + "可以在跳板机执行 apk add netcat-openbsd，"
                    + "或者把 sshd_config 里的 AllowTcpForwarding 改成 yes");
        }
        jumpMode = JumpMode.EXEC;
        jumpTool = tool;
        jumpCommand = tunnelCommand(tool, cfg.host, cfg.port);
    }

    private boolean canDirectTcpip(String host, int port) {
        try {
            ChannelDirectTCPIP ch = (ChannelDirectTCPIP) jumpSession.openChannel("direct-tcpip");
            ch.setHost(host);
            ch.setPort(port);
            ch.setOrgIPAddress("127.0.0.1");
            ch.setOrgPort(0);
            ch.connect(8000);
            ch.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 看看跳板机上有什么可用的管道工具 */
    private String detectTunnelTool() {
        String out = runOnJump("command -v nc; command -v ncat; command -v socat");
        if (out.contains("socat")) return "socat";
        if (out.contains("ncat")) return "ncat";
        if (out.contains("nc")) return "nc";
        return null;
    }

    private static String tunnelCommand(String tool, String host, int port) {
        if ("socat".equals(tool)) return "socat - TCP:" + host + ":" + port;
        return tool + " '" + host + "' " + port;
    }

    /** 在跳板机上跑一条命令并收集输出（最多等 4 秒） */
    private String runOnJump(String command) {
        ChannelExec ex = null;
        try {
            ex = (ChannelExec) jumpSession.openChannel("exec");
            ex.setCommand(command);
            InputStream in = ex.getInputStream();
            ex.connect(5000);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline) {
                int avail = in.available();
                if (avail > 0) {
                    int r = in.read(buf, 0, Math.min(avail, buf.length));
                    if (r > 0) bos.write(buf, 0, r);
                } else if (ex.isClosed()) {
                    break;
                } else {
                    Thread.sleep(50);
                }
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (ex != null) {
                try {
                    ex.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 把 JSch 的 TCP 连接接到跳板机的 direct-tcpip 通道上。
     * JSch 只通过 getInputStream / getOutputStream 拿流，
     * 所以返回一个没真正连过的 Socket 当占位就够了。
     */
    private final class JumpSocketFactory implements SocketFactory {
        private final Session jump;
        private final Socket placeholder = new Socket();
        private InputStream in;
        private OutputStream out;

        JumpSocketFactory(Session jump) {
            this.jump = jump;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            try {
                if (jumpMode == JumpMode.EXEC) {
                    ChannelExec ex = (ChannelExec) jump.openChannel("exec");
                    ex.setCommand(jumpCommand);
                    in = ex.getInputStream();
                    out = ex.getOutputStream();
                    ex.connect(10000);
                } else {
                    ChannelDirectTCPIP ch = (ChannelDirectTCPIP) jump.openChannel("direct-tcpip");
                    ch.setHost(host);
                    ch.setPort(port);
                    ch.setOrgIPAddress("127.0.0.1");
                    ch.setOrgPort(0);
                    ch.connect(10000);
                    in = ch.getInputStream();
                    out = ch.getOutputStream();
                }
                return placeholder;
            } catch (Exception e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.contains("channel is not opened") || m.contains("open failed")) {
                    m = "跳板机连不到 " + host + ":" + port
                            + "，请在跳板机上执行 nc -vz " + host + " " + port + " 确认网络";
                }
                throw new IOException("跳板机转发失败：" + m, e);
            }
        }

        @Override
        public InputStream getInputStream(Socket socket) throws IOException {
            if (in == null) throw new IOException("隧道未建立");
            return in;
        }

        @Override
        public OutputStream getOutputStream(Socket socket) throws IOException {
            if (out == null) throw new IOException("隧道未建立");
            return out;
        }
    }

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

    private void pump(InputStream in) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while (!closed && (n = in.read(buf)) > 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                listener.onOutput(chunk);
            }
        } catch (Exception ignored) {
        }
        if (!closed) listener.onState("closed", null);
    }

    /* ------------------------------------------------------------- 输入 */

    /** 只入队，立刻返回；真正的 write 在 writer 线程里做 */
    public void write(byte[] data) {
        if (data == null || data.length == 0 || closed) return;
        outQueue.add(data);
    }

    /** 同样入队：改窗口大小也要发一个 SSH 包出去 */
    public void resize(int cols, int rows) {
        if (cols <= 0 || rows <= 0) return;
        cfg.cols = cols;
        cfg.rows = rows;
        if (!closed) outQueue.add(new int[]{cols, rows});
    }

    private void pumpOut() {
        try {
            while (!closed) {
                Object item = outQueue.take();
                if (closed) return;
                if (item instanceof byte[]) {
                    OutputStream o = out;
                    if (o == null) continue;
                    o.write((byte[]) item);
                    o.flush();
                } else if (item instanceof int[]) {
                    int[] size = (int[]) item;
                    ChannelShell ch = shell;
                    if (ch != null) {
                        ch.setPtySize(size[0], size[1], size[0] * 8, size[1] * 16);
                    }
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            if (!closed) listener.onState("error", "写入失败：" + e.getMessage());
        }
    }

    public boolean isConnected() {
        return session != null && session.isConnected() && !closed;
    }

    /* ------------------------------------------------------------- SFTP */

    /** 打开一条 SFTP 通道；调用方负责在后台线程用，并在结束时 disconnect。 */
    public ChannelSftp openSftp() throws JSchException {
        Session s = session;
        if (s == null || !s.isConnected()) throw new JSchException("连接已断开");
        ChannelSftp sftp = (ChannelSftp) s.openChannel("sftp");
        sftp.connect(15000);
        return sftp;
    }

    /**
     * 在已连的会话上跑一条 exec 命令并返回 stdout+stderr 全文；调用方负责在后台线程用。
     * 超时返回已读到的部分并关掉通道；会话断开时抛 JSchException。
     */
    public String exec(String command, long timeoutMs) throws JSchException {
        Session s = session;
        if (s == null || !s.isConnected()) throw new JSchException("连接已断开");
        ChannelExec ex = null;
        try {
            ex = (ChannelExec) s.openChannel("exec");
            ex.setCommand(command);
            InputStream in = ex.getInputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ex.setErrStream(err);
            ex.connect(10000);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
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
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            while (in.available() > 0) {
                int r = in.read(buf, 0, Math.min(in.available(), buf.length));
                if (r <= 0) break;
                out.write(buf, 0, r);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8)
                    + "\n" + new String(err.toByteArray(), StandardCharsets.UTF_8);
        } catch (JSchException e) {
            throw e;
        } catch (Exception e) {
            throw new JSchException("exec 失败：" + e.getMessage());
        } finally {
            if (ex != null) {
                try {
                    ex.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void close() {
        if (closed) return;
        closed = true;

        final ChannelShell ch = shell;
        final Session s = session;
        final Session js = jumpSession;
        jumpSession = null;
        shell = null;
        session = null;
        out = null;
        outQueue.clear();

        Thread w = writer;
        if (w != null) w.interrupt();

        // disconnect 也会发包，不能在主线程做
        Thread t = new Thread(() -> {
            try {
                if (ch != null) ch.disconnect();
            } catch (Exception ignored) {
            }
            try {
                if (s != null) s.disconnect();
            } catch (Exception ignored) {
            }
            try {
                if (js != null) js.disconnect();
            } catch (Exception ignored) {
            }
        }, "ssh-close");
        t.setDaemon(true);
        t.start();
    }

    /* --------------------------------------------------------- 主机密钥 */

    private final class StoreHostKeys implements HostKeyRepository {

        private final String hostPort;

        StoreHostKeys(String hostPort) {
            this.hostPort = hostPort;
        }

        @Override
        public int check(String host, byte[] key) {
            String fp = fingerprint(key);
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
            KnownHosts.trust(hostPort, fingerprintOf(hostkey.getKey()));
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

    /** HostKey.getKey() 给的是 base64 字符串，先解码再算摘要 */
    public static String fingerprintOf(String base64Key) {
        try {
            return fingerprint(Base64.getDecoder().decode(base64Key));
        } catch (Exception e) {
            return "SHA256:?";
        }
    }

    /** SHA256:base64 —— 和 `ssh-keygen -lf` 的输出一致 */
    public static String fingerprint(byte[] keyBlob) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(keyBlob);
            String b64 = Base64.getEncoder().encodeToString(digest);
            while (b64.endsWith("=")) b64 = b64.substring(0, b64.length() - 1);
            return "SHA256:" + b64;
        } catch (Exception e) {
            return "SHA256:?";
        }
    }

    private final class Prompt implements UserInfo, UIKeyboardInteractive {

        private final Config conf;

        Prompt() {
            this(cfg);
        }

        Prompt(Config conf) {
            this.conf = conf;
        }

        @Override
        public String getPassphrase() {
            return conf.passphrase;
        }

        @Override
        public String getPassword() {
            return conf.password;
        }

        @Override
        public boolean promptPassword(String message) {
            return conf.password != null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return conf.passphrase != null;
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
            // 二次验证（OTP 之类）在这里做不了，只能把密码填给第一问
            String[] answers = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                answers[i] = (i == 0 && conf.password != null) ? conf.password : "";
            }
            return answers;
        }
    }

    /** 去掉 JSch 包在外面的 "Session.connect: java.io.IOException:" 前缀 */
    private static String clean(String m) {
        if (m == null || m.isEmpty()) return "连接失败";
        String s = m;
        int i = s.lastIndexOf("java.io.IOException: ");
        if (i >= 0) s = s.substring(i + "java.io.IOException: ".length());
        i = s.lastIndexOf("Session.connect: ");
        if (i >= 0) s = s.substring(i + "Session.connect: ".length());
        return s;
    }

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
        return m.isEmpty() ? "连接失败" : clean(m);
    }
}
