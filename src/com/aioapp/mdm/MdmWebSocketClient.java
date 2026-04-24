package com.aioapp.mdm;

import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class MdmWebSocketClient {
    private static final String TAG = "MdmWebSocketClient";

    // Backoff: 1s, 2s, 4s, 8s, 30s cap
    private static final long[] BACKOFF_MS = {1_000, 2_000, 4_000, 8_000, 30_000};
    // Reset backoff if connected for this long without dropping
    private static final long BACKOFF_RESET_MS = 60_000;

    interface Listener {
        void onMessage(JSONObject message);
    }

    private final String host;
    private final int port;
    private final boolean useTls;
    private final String wsPath; // e.g. /api/v1/ws?serial=DEVICE-001
    private final String apiKey;

    private Listener listener;
    private volatile boolean running = false;
    private volatile Thread loopThread;
    private volatile Socket socket;
    private volatile OutputStream outputStream;
    private int reconnectAttempt = 0;
    private long connectedAt = 0;

    /**
     * @param apiBaseUrl  The HTTP base URL, e.g. "http://10.32.0.246:8080"
     * @param serial      Device serial number used as the WS query param
     * @param apiKey      Value for the X-API-Key header
     */
    public MdmWebSocketClient(String apiBaseUrl, String serial, String apiKey) {
        try {
            URL url = new URL(apiBaseUrl);
            this.host = url.getHost();
            this.useTls = url.getProtocol().equalsIgnoreCase("https");
            int p = url.getPort();
            this.port = (p == -1) ? (this.useTls ? 443 : 80) : p;
            this.wsPath = "/api/v1/ws?serial=" + URLEncoder.encode(serial, "UTF-8");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid apiBaseUrl: " + apiBaseUrl, e);
        }
        this.apiKey = apiKey;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void start() {
        running = true;
        Thread t = new Thread(this::connectLoop, "mdm-ws");
        t.setDaemon(true);
        loopThread = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = loopThread;
        if (t != null) t.interrupt();
        closeSocket();
    }

    private void connectLoop() {
        while (running) {
            try {
                connect();
            } catch (Exception e) {
                Log.w(TAG, "Connection error: " + e.getMessage());
            } finally {
                closeSocket();
            }
            if (!running) break;
            long delay = BACKOFF_MS[Math.min(reconnectAttempt, BACKOFF_MS.length - 1)];
            Log.i(TAG, "Reconnecting in " + (delay / 1000) + "s (attempt " + (reconnectAttempt + 1) + ")");
            reconnectAttempt++;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void connect() throws Exception {
        Socket s;
        if (useTls) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ssl.connect(new InetSocketAddress(host, port), 10_000);
            ssl.startHandshake();
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10_000);
        }
        s.setSoTimeout(90_000); // > 2× server ping interval (45s)
        this.socket = s;
        OutputStream out = s.getOutputStream();
        this.outputStream = out;

        // --- HTTP Upgrade handshake ---
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        String handshake =
                "GET " + wsPath + " HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + wsKey + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "X-API-Key: " + apiKey + "\r\n" +
                "\r\n";
        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read HTTP response headers until \r\n\r\n
        InputStream in = s.getInputStream();
        StringBuilder header = new StringBuilder();
        int prev3 = -1, prev2 = -1, prev1 = -1, b;
        while ((b = in.read()) != -1) {
            header.append((char) b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
            prev3 = prev2;
            prev2 = prev1;
            prev1 = b;
        }
        if (!header.toString().contains(" 101 ")) {
            throw new IOException("WebSocket upgrade failed: " + header.toString().split("\r\n")[0]);
        }

        Log.i(TAG, "WebSocket connected to " + host + ":" + port);
        connectedAt = System.currentTimeMillis();
        reconnectAttempt = 0;

        // --- WebSocket frame read loop ---
        DataInputStream dis = new DataInputStream(in);
        while (running) {
            int byte0 = dis.read();
            int byte1 = dis.read();
            if (byte0 == -1 || byte1 == -1) break;

            int opcode = byte0 & 0x0F;
            boolean masked = (byte1 & 0x80) != 0;
            long payloadLen = byte1 & 0x7F;
            if (payloadLen == 126) {
                payloadLen = dis.readUnsignedShort();
            } else if (payloadLen == 127) {
                payloadLen = dis.readLong();
            }

            byte[] maskKey = masked ? new byte[4] : null;
            if (masked) dis.readFully(maskKey);

            byte[] payload = new byte[(int) payloadLen];
            dis.readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];
            }

            // Reset backoff once the connection has been stable for 60s
            if (System.currentTimeMillis() - connectedAt > BACKOFF_RESET_MS) {
                reconnectAttempt = 0;
            }

            switch (opcode) {
                case 0x1: { // text frame
                    String text = new String(payload, StandardCharsets.UTF_8);
                    try {
                        JSONObject msg = new JSONObject(text);
                        if (listener != null) listener.onMessage(msg);
                    } catch (Exception e) {
                        Log.w(TAG, "Malformed JSON frame: " + e.getMessage());
                    }
                    break;
                }
                case 0x9: // ping — must respond with pong
                    sendFrame(0xA, payload);
                    break;
                case 0x8: // close
                    Log.i(TAG, "Server closed the WebSocket");
                    return; // exit connect() so connectLoop() can reconnect
            }
        }
    }

    public void send(String json) {
        sendFrame(0x1, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends a masked WebSocket frame. Client → server frames must always be masked per RFC 6455. */
    private void sendFrame(int opcode, byte[] payload) {
        OutputStream out = this.outputStream;
        if (out == null) return;
        try {
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            byte[] maskedPayload = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) maskedPayload[i] = (byte) (payload[i] ^ mask[i % 4]);

            ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 10);
            buf.write(0x80 | opcode); // FIN=1
            if (payload.length < 126) {
                buf.write(0x80 | payload.length);
            } else if (payload.length < 65536) {
                buf.write(0x80 | 126);
                buf.write((payload.length >> 8) & 0xFF);
                buf.write(payload.length & 0xFF);
            } else {
                buf.write(0x80 | 127);
                long len = payload.length;
                for (int i = 7; i >= 0; i--) buf.write((int) ((len >> (i * 8)) & 0xFF));
            }
            buf.write(mask);
            buf.write(maskedPayload);
            synchronized (out) {
                out.write(buf.toByteArray());
                out.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "sendFrame failed opcode=0x" + Integer.toHexString(opcode)
                    + " payloadLen=" + payload.length + ": " + e.getMessage());
            closeSocket();
        }
    }

    private void closeSocket() {
        try {
            Socket s = this.socket;
            if (s != null) s.close();
        } catch (Exception ignored) {}
        this.socket = null;
        this.outputStream = null;
    }
}
