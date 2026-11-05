package com.castapp.tv;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny HTTP + WebSocket server with no dependencies beyond the JDK.
 *
 * GET /    -> the TV receiver page
 * GET /ws  -> WebSocket that streams binary messages:
 *             [0x01][JPEG bytes]  one video frame
 *             [0x02][PCM bytes]   16-bit little-endian interleaved audio
 *             plus text messages containing JSON config.
 *
 * Each viewer gets its own sender thread. Video is "latest frame wins" so a
 * slow TV never builds up lag; audio is a short bounded queue.
 */
public class StreamServer {

    public interface Listener {
        void onViewerCountChanged(int count);
    }

    public static final byte TYPE_VIDEO = 1;
    public static final byte TYPE_AUDIO = 2;

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_AUDIO_QUEUE = 25; // ~0.5 s of 20 ms chunks

    private final byte[] pageHtml;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private volatile Listener listener;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile String configJson = "{}";
    private volatile byte[] lastFrame;

    public StreamServer(byte[] pageHtml) {
        this.pageHtml = pageHtml;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Starts listening on the first free port starting at preferredPort. Returns the port. */
    public synchronized int start(int preferredPort) throws IOException {
        if (running) return serverSocket.getLocalPort();
        IOException last = null;
        for (int port = preferredPort; port < preferredPort + 20; port++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                serverSocket = ss;
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        running = true;
        Thread t = new Thread(this::acceptLoop, "cast-accept");
        t.setDaemon(true);
        t.start();
        return serverSocket.getLocalPort();
    }

    public int getPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients) c.close();
        clients.clear();
        lastFrame = null;
        notifyCount();
    }

    public boolean hasViewers() {
        return !clients.isEmpty();
    }

    public int viewerCount() {
        return clients.size();
    }

    public void setConfig(String json) {
        configJson = json;
        for (Client c : clients) c.offerText(json);
    }

    public void broadcastVideo(byte[] jpeg, int len) {
        byte[] frame = new byte[len + 1];
        frame[0] = TYPE_VIDEO;
        System.arraycopy(jpeg, 0, frame, 1, len);
        lastFrame = frame;
        for (Client c : clients) c.offerVideo(frame);
    }

    public void broadcastAudio(byte[] pcm, int len) {
        if (clients.isEmpty()) return;
        byte[] msg = new byte[len + 1];
        msg[0] = TYPE_AUDIO;
        System.arraycopy(pcm, 0, msg, 1, len);
        for (Client c : clients) c.offerAudio(msg);
    }

    private void notifyCount() {
        Listener l = listener;
        if (l != null) l.onViewerCountChanged(clients.size());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s), "cast-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) {
                s.close();
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) {
                    headers.put(line.substring(0, i).trim().toLowerCase(Locale.US), line.substring(i + 1).trim());
                }
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            String upgrade = headers.get("upgrade");
            if (path.equals("/ws") && upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
                upgradeToWebSocket(s, headers.get("sec-websocket-key"));
            } else if (path.equals("/") || path.equals("/index.html")) {
                sendHttp(s, "200 OK", "text/html; charset=utf-8", pageHtml);
            } else if (path.equals("/favicon.ico")) {
                sendHttp(s, "204 No Content", "text/plain", new byte[0]);
            } else {
                sendHttp(s, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            closeQuietly(s);
        }
    }

    private static void sendHttp(Socket s, String status, String type, byte[] body) throws IOException {
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        s.close();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void upgradeToWebSocket(Socket s, String key) throws IOException {
        if (key == null) {
            sendHttp(s, "400 Bad Request", "text/plain", new byte[0]);
            return;
        }
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            accept = Base64.getEncoder().encodeToString(
                    sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        s.setSoTimeout(0);

        Client c = new Client(s);
        c.offerText(configJson);
        byte[] lf = lastFrame;
        if (lf != null) c.offerVideo(lf); // screen may be static; show something immediately
        clients.add(c);
        notifyCount();
        c.start();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ----------------------------------------------------------- WebSocket

    private class Client {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object lock = new Object();
        private final ArrayDeque<byte[]> audio = new ArrayDeque<>();
        private final ArrayDeque<String> texts = new ArrayDeque<>();
        private byte[] video;
        private byte[] pong;
        private boolean closed;

        Client(Socket s) throws IOException {
            socket = s;
            in = s.getInputStream();
            out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
        }

        void start() {
            Thread w = new Thread(this::writeLoop, "cast-ws-write");
            w.setDaemon(true);
            w.start();
            Thread r = new Thread(this::readLoop, "cast-ws-read");
            r.setDaemon(true);
            r.start();
        }

        void offerVideo(byte[] f) {
            synchronized (lock) {
                video = f; // drop any frame the TV hasn't received yet
                lock.notify();
            }
        }

        void offerAudio(byte[] a) {
            synchronized (lock) {
                while (audio.size() >= MAX_AUDIO_QUEUE) audio.poll();
                audio.add(a);
                lock.notify();
            }
        }

        void offerText(String t) {
            synchronized (lock) {
                texts.add(t);
                lock.notify();
            }
        }

        void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                lock.notify();
            }
            closeQuietly(socket);
            if (clients.remove(this)) notifyCount();
        }

        private void writeLoop() {
            try {
                while (true) {
                    String text = null;
                    byte[] a = null, v = null, p = null;
                    synchronized (lock) {
                        while (!closed && texts.isEmpty() && audio.isEmpty() && video == null && pong == null) {
                            lock.wait();
                        }
                        if (closed) return;
                        p = pong;
                        pong = null;
                        text = texts.poll();
                        // Audio first: it's small and gaps are more noticeable than a skipped frame.
                        a = audio.poll();
                        if (a == null) {
                            v = video;
                            video = null;
                        }
                    }
                    if (p != null) writeFrame(0xA, p, 0, p.length);
                    if (text != null) {
                        byte[] b = text.getBytes(StandardCharsets.UTF_8);
                        writeFrame(0x1, b, 0, b.length);
                    }
                    if (a != null) writeFrame(0x2, a, 0, a.length);
                    if (v != null) writeFrame(0x2, v, 0, v.length);
                    out.flush();
                }
            } catch (Exception e) {
                close();
            }
        }

        private void writeFrame(int opcode, byte[] data, int off, int len) throws IOException {
            out.write(0x80 | opcode);
            if (len < 126) {
                out.write(len);
            } else if (len < 65536) {
                out.write(126);
                out.write((len >>> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
            out.write(data, off, len);
        }

        private void readLoop() {
            try {
                while (true) {
                    int b0 = in.read();
                    int b1 = in.read();
                    if (b0 < 0 || b1 < 0) break;
                    int opcode = b0 & 0x0F;
                    boolean masked = (b1 & 0x80) != 0;
                    long len = b1 & 0x7F;
                    if (len == 126) {
                        len = (readByte() << 8) | readByte();
                    } else if (len == 127) {
                        len = 0;
                        for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                    }
                    if (len > 1 << 20) break; // viewers only send tiny control frames
                    byte[] mask = new byte[4];
                    if (masked) readFully(mask);
                    byte[] payload = new byte[(int) len];
                    readFully(payload);
                    if (masked) {
                        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                    }
                    if (opcode == 0x8) break;          // close
                    if (opcode == 0x9) {               // ping -> pong
                        synchronized (lock) {
                            pong = payload;
                            lock.notify();
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            close();
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new SocketException("eof");
            return b;
        }

        private void readFully(byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new SocketException("eof");
                off += n;
            }
        }
    }
}
