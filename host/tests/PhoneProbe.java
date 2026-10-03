// The phone app's terminal connection, for the host's end-to-end tests: the same SSH library (JSch) and the same
// settings and steps as app/.../ssh/SshConnection.kt, with the phone's terminal answering queries as Termux does.
//
//   java -cp jsch.jar PhoneProbe.java genkey KEYFILE
//   java -cp jsch.jar PhoneProbe.java run PORT KEYFILE SESSION [clip] [flood]
//   java -cp jsch.jar PhoneProbe.java stall PORT KEYFILE SESSION SECONDS
//
// "run" types a command a key at a time (as the keyboard does), several rounds, and checks that each one's output
// comes back; "clip" also polls the PC clipboard on the same connection every 2 s, as the app does; "flood" then
// prints a few MB and waits for the end of it. "stall" is a phone that stopped reading (asleep, or a link that
// died without closing): it attaches and then holds the connection without reading anything.
import com.jcraft.jsch.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class PhoneProbe {
    static final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    static int seen() { synchronized (screen) { return screen.size(); } }

    static long waitFor(String needle, int from, long ms) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            String s;
            synchronized (screen) { s = new String(screen.toByteArray(), StandardCharsets.ISO_8859_1); }
            if (s.indexOf(needle, from) >= 0) return System.currentTimeMillis() - t0;
            Thread.sleep(20);
        }
        return -1;
    }

    static void send(ExecutorService writer, OutputStream out, byte[] bytes) {
        writer.execute(() -> {
            try { out.write(bytes); out.flush(); } catch (IOException ignored) { }
        });
    }

    static String exec(Session s, String command, long timeoutMs) throws Exception {
        ChannelExec ch = (ChannelExec) s.openChannel("exec");
        ch.setCommand(command);
        InputStream in = ch.getInputStream();
        ch.connect(10_000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long t0 = System.currentTimeMillis();
        byte[] buf = new byte[8192];
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            while (in.available() > 0) out.write(buf, 0, in.read(buf));
            if (ch.isClosed()) break;
            Thread.sleep(20);
        }
        ch.disconnect();
        return out.toString(StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("genkey")) {
            KeyPair kp = KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256);
            kp.writePrivateKey(a[1]);
            kp.writePublicKey(a[1] + ".pub", "probe");
            return;
        }
        int port = Integer.parseInt(a[1]);
        boolean stall = a[0].equals("stall");
        java.util.List<String> opts = java.util.Arrays.asList(a).subList(4, a.length);
        boolean clip = opts.contains("clip"), flood = opts.contains("flood");

        JSch jsch = new JSch();
        jsch.addIdentity(a[2]);
        Session s = jsch.getSession("pc", "127.0.0.1", port);
        s.setConfig("StrictHostKeyChecking", "no");
        s.setConfig("PreferredAuthentications", "publickey");
        s.setConfig("compression.s2c", "zlib@openssh.com,zlib,none");
        s.setConfig("compression.c2s", "zlib@openssh.com,zlib,none");
        s.setConfig("compression_level", "6");
        s.setServerAliveInterval(30_000);
        s.setServerAliveCountMax(3);
        s.setTimeout(0);
        s.connect(20_000);

        ChannelShell ch = (ChannelShell) s.openChannel("shell");
        ch.setPtyType("xterm-256color", 52, 40, 0, 0);
        ch.setEnv("COLORTERM", "truecolor");
        ch.setEnv("AS_SESSION", a[3]);
        ch.setEnv("AS_CMD", "");
        InputStream in = ch.getInputStream();
        OutputStream out = ch.getOutputStream();
        ch.connect(15_000);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        if (stall) {
            System.out.println("stalled");
            Thread.sleep(Long.parseLong(a[4]) * 1000);
            System.exit(0);
        }

        Thread reader = new Thread(() -> {
            byte[] buf = new byte[32 * 1024];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (screen) { screen.write(buf, 0, n); }
                    String chunk = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                    // Termux's answers to "who are you" and "where is the cursor"
                    if (chunk.contains("\u001b[c")) send(writer, out, "\u001b[?64;1;2;6;9;15;18;21;22c".getBytes());
                    if (chunk.contains("\u001b[6n")) send(writer, out, "\u001b[1;1R".getBytes());
                }
            } catch (IOException ignored) { }
        });
        reader.setDaemon(true);
        reader.start();

        if (clip) {
            Thread poll = new Thread(() -> {
                while (true) {
                    try { exec(s, "mc clip read", 5_000); Thread.sleep(2_000); } catch (Exception e) { return; }
                }
            });
            poll.setDaemon(true);
            poll.start();
        }

        long prompt = waitFor("PS ", 0, 20_000);
        System.out.println("prompt after " + prompt + " ms");
        boolean ok = prompt >= 0;
        for (int round = 1; round <= 4 && ok; round++) {
            int mark = seen();
            String marker = "r" + round + "-ok";
            // the command shows the marker only when it runs: ("r1"+"-ok")
            String line = "echo (\"r" + round + "\"+\"-ok\")\r";
            for (byte b : line.getBytes()) {
                send(writer, out, new byte[] { b });
                Thread.sleep(80);
            }
            long ran = waitFor(marker, mark, 10_000);
            System.out.println("round " + round + ": output after " + ran + " ms");
            ok = ran >= 0;
            Thread.sleep(3_000);
        }
        if (ok && flood) {
            int mark = seen();
            String line = "1..60000|%{\"line $_ \"+(\"x\"*70)}; echo (\"fl\"+\"ood-ok\")\r";
            send(writer, out, line.getBytes());
            long done = waitFor("flood-ok", mark, 60_000);
            System.out.println("flood: end after " + done + " ms");
            ok = done >= 0;
        }
        if (!ok) {
            String all;
            synchronized (screen) { all = new String(screen.toByteArray(), StandardCharsets.ISO_8859_1); }
            String tail = all.substring(Math.max(0, all.length() - 600)).replace("\u001b", "\\e").replace("\r", "\\r").replace("\n", "\\n");
            System.out.println("screen tail: " + tail);
        }
        System.out.println(ok ? "RESULT ok" : "RESULT fail");
        ch.disconnect();
        s.disconnect();
        writer.shutdownNow();
    }
}
