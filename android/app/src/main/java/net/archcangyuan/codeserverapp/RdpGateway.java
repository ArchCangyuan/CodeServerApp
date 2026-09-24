package net.archcangyuan.codeserverapp;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Loopback gateway for the built-in remote desktop client. It serves the
 * IronRDP web client page and implements the RDCleanPath WebSocket gateway the
 * client connects to: it opens the Cloudflare Access tunnel to the host, sends
 * the X.224 connection request, performs the TLS handshake with the RDP
 * server, returns the server certificate chain, and then relays the decrypted
 * stream. Tokens and credentials never leave the app.
 */
final class RdpGateway {
    /** Receives gateway events; may be called on any thread. */
    interface Listener {
        void onLoginRequired(String host);
    }

    /** Platform services the gateway needs; replaceable in tests. */
    interface Environment {
        InputStream openAsset(String name) throws IOException;

        String loadToken(String host);

        void clearToken(String host);

        AccessWebSocket openTunnel(String host, String token) throws IOException;
    }

    private static final String ASSET_DIRECTORY = "rdp/";
    private static final int TLS_PORT = 3389;
    private static RdpGateway instance;

    private final Environment environment;
    private final ServerSocket server;
    private final Map<String, String> sessionHosts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile Listener listener;

    RdpGateway(Environment environment) throws IOException {
        this.environment = environment;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0));
        Thread acceptThread = new Thread(this::acceptLoop, "RdpGateway-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    static synchronized RdpGateway get(Context context) throws IOException {
        if (instance == null || instance.server.isClosed()) {
            Context appContext = context.getApplicationContext();
            AssetManager assets = appContext.getAssets();
            instance = new RdpGateway(new Environment() {
                @Override
                public InputStream openAsset(String name) throws IOException {
                    return assets.open(ASSET_DIRECTORY + name);
                }

                @Override
                public String loadToken(String host) {
                    return AccessTokenStore.loadToken(appContext, host);
                }

                @Override
                public void clearToken(String host) {
                    AccessTokenStore.clearToken(appContext, host);
                }

                @Override
                public AccessWebSocket openTunnel(String host, String token) throws IOException {
                    return AccessWebSocket.connect(host, token);
                }
            });
        }
        return instance;
    }

    void close() {
        closeQuietly(server);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    int port() {
        return server.getLocalPort();
    }

    String origin() {
        return "http://127.0.0.1:" + port();
    }

    /** Registers a session for {@code host} and returns its secret gateway token. */
    String newSession(String host) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessionHosts.put(token, host.toLowerCase(Locale.US));
        return token;
    }

    String pageUrl(String sessionToken) {
        return origin() + "/rdp.html#" + sessionToken;
    }

    String proxyAddress() {
        return "ws://127.0.0.1:" + port() + "/gw";
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread handler = new Thread(() -> handle(client), "RdpGateway-http");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException exception) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(15_000);
            InputStream input = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(input);
            String[] parts = requestLine.split(" ");
            String path = parts.length >= 2 ? parts[1] : "/";
            String webSocketKey = null;
            boolean upgrade = false;
            for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                if (name.equals("sec-websocket-key")) {
                    webSocketKey = value;
                } else if (name.equals("upgrade") && value.equalsIgnoreCase("websocket")) {
                    upgrade = true;
                }
            }
            if (upgrade && webSocketKey != null && path.startsWith("/gw")) {
                AccessWebSocket webSocket = AccessWebSocket.acceptServer(client, input, webSocketKey);
                runCleanPathSession(webSocket);
            } else {
                serveAsset(client, path);
            }
        } catch (IOException exception) {
            closeQuietly(client);
        }
    }

    private void serveAsset(Socket client, String path) throws IOException {
        int query = path.indexOf('?');
        String name = (query >= 0 ? path.substring(0, query) : path).replaceFirst("^/+", "");
        if (name.isEmpty()) {
            name = "rdp.html";
        }
        OutputStream output = client.getOutputStream();
        if (!name.matches("[A-Za-z0-9._-]+")) {
            writeStatus(output, "404 Not Found");
            closeQuietly(client);
            return;
        }
        byte[] body;
        try (InputStream asset = environment.openAsset(name)) {
            body = readAll(asset);
        } catch (IOException exception) {
            writeStatus(output, "404 Not Found");
            closeQuietly(client);
            return;
        }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
            : name.endsWith(".js") ? "text/javascript; charset=utf-8"
            : "application/octet-stream";
        String headers = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-cache\r\n"
            + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
        closeQuietly(client);
    }

    private static void writeStatus(OutputStream output, String status) throws IOException {
        output.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    /** One RDCleanPath session: handshake, TLS to the RDP server, then relay. */
    private void runCleanPathSession(AccessWebSocket client) {
        AccessWebSocket tunnel = null;
        Socket[] pair = null;
        SSLSocket tls = null;
        String host = null;
        try {
            RdCleanPath.Request request = readRequest(client);
            host = sessionHosts.get(request.proxyAuth);
            if (host == null) {
                sendPdu(client, RdCleanPath.encodeGeneralError(403));
                return;
            }
            String token = environment.loadToken(host);
            if (token == null) {
                notifyLoginRequired(host);
                sendPdu(client, RdCleanPath.encodeGeneralError(401));
                return;
            }
            try {
                tunnel = environment.openTunnel(host, token);
            } catch (AccessWebSocket.LoginRequiredException exception) {
                environment.clearToken(host);
                notifyLoginRequired(host);
                sendPdu(client, RdCleanPath.encodeGeneralError(401));
                return;
            } catch (IOException exception) {
                sendPdu(client, RdCleanPath.encodeGeneralError(502));
                return;
            }

            TunnelInputStream tunnelInput = new TunnelInputStream(tunnel);
            tunnel.sendBinary(request.x224ConnectionRequest, 0, request.x224ConnectionRequest.length);
            byte[] x224Response = readTpkt(tunnelInput);
            if (x224Response.length >= 12 && (x224Response[11] & 0xFF) == 0x03) {
                // RDP_NEG_FAILURE: let the client explain (e.g. CredSSP required).
                sendPdu(client, RdCleanPath.encodeNegotiationError(x224Response));
                return;
            }

            pair = socketPair();
            pump(tunnelInput, pair[1], tunnel);
            tls = (SSLSocket) trustAllContext().getSocketFactory()
                .createSocket(pair[0], host, TLS_PORT, true);
            tls.startHandshake();
            List<byte[]> chain = new ArrayList<>();
            for (Certificate certificate : tls.getSession().getPeerCertificates()) {
                chain.add(certificate.getEncoded());
            }
            sendPdu(client, RdCleanPath.encodeResponse(host + ":" + TLS_PORT, x224Response, chain));

            relay(client, tls);
        } catch (Exception exception) {
            try {
                sendPdu(client, RdCleanPath.encodeGeneralError(null));
            } catch (IOException ignored) {
                // The client is gone.
            }
        } finally {
            client.close();
            if (tls != null) {
                closeQuietly(tls);
            }
            if (pair != null) {
                closeQuietly(pair[0]);
                closeQuietly(pair[1]);
            }
            if (tunnel != null) {
                tunnel.close();
            }
        }
    }

    private void notifyLoginRequired(String host) {
        Listener current = listener;
        if (current != null) {
            current.onLoginRequired(host);
        }
    }

    private static RdCleanPath.Request readRequest(AccessWebSocket client) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            byte[] message = client.readMessage();
            if (message == null) {
                throw new EOFException("Client closed before the RDCleanPath request");
            }
            buffer.write(message);
            byte[] data = buffer.toByteArray();
            int length = RdCleanPath.pduLength(data, data.length);
            if (length > 0 && data.length >= length) {
                byte[] pdu = new byte[length];
                System.arraycopy(data, 0, pdu, 0, length);
                return RdCleanPath.decodeRequest(pdu);
            }
            if (data.length > 64 * 1024) {
                throw new IOException("RDCleanPath request too large");
            }
        }
    }

    private static void sendPdu(AccessWebSocket client, byte[] pdu) throws IOException {
        client.sendBinary(pdu, 0, pdu.length);
    }

    /** Reads one TPKT-framed PDU (the X.224 Connection Confirm). */
    private static byte[] readTpkt(InputStream input) throws IOException {
        byte[] header = new byte[4];
        readFully(input, header, 0, 4);
        if (header[0] != 0x03) {
            throw new IOException("Unexpected X.224 response");
        }
        int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length < 4 || length > 4096) {
            throw new IOException("Invalid X.224 response length");
        }
        byte[] pdu = new byte[length];
        System.arraycopy(header, 0, pdu, 0, 4);
        readFully(input, pdu, 4, length - 4);
        return pdu;
    }

    /** Connects two loopback sockets so TLS can run over the tunnel stream. */
    private static Socket[] socketPair() throws IOException {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0));
            Socket first = new Socket();
            first.connect(listener.getLocalSocketAddress(), 5_000);
            Socket second = listener.accept();
            first.setTcpNoDelay(true);
            second.setTcpNoDelay(true);
            return new Socket[] { first, second };
        }
    }

    /** Copies the tunnel stream to {@code socket} and the socket back into the tunnel. */
    private static void pump(TunnelInputStream tunnelInput, Socket socket, AccessWebSocket tunnel) {
        Thread down = new Thread(() -> {
            byte[] buffer = new byte[32 * 1024];
            try (OutputStream output = socket.getOutputStream()) {
                for (int count = tunnelInput.read(buffer); count >= 0; count = tunnelInput.read(buffer)) {
                    output.write(buffer, 0, count);
                    output.flush();
                }
            } catch (IOException ignored) {
                // Closed.
            } finally {
                closeQuietly(socket);
            }
        }, "RdpGateway-tunnel-down");
        Thread up = new Thread(() -> {
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = socket.getInputStream()) {
                for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                    if (count > 0) {
                        tunnel.sendBinary(buffer, 0, count);
                    }
                }
            } catch (IOException ignored) {
                // Closed.
            } finally {
                tunnel.close();
            }
        }, "RdpGateway-tunnel-up");
        down.setDaemon(true);
        up.setDaemon(true);
        down.start();
        up.start();
    }

    /** Relays client WebSocket messages to the TLS stream and back until either side closes. */
    private static void relay(AccessWebSocket client, SSLSocket tls) throws IOException {
        Thread downstream = new Thread(() -> {
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = tls.getInputStream()) {
                for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                    if (count > 0) {
                        client.sendBinary(buffer, 0, count);
                    }
                }
            } catch (IOException ignored) {
                // Closed.
            } finally {
                client.close();
            }
        }, "RdpGateway-relay-down");
        downstream.setDaemon(true);
        downstream.start();
        OutputStream output = tls.getOutputStream();
        for (byte[] message = client.readMessage(); message != null; message = client.readMessage()) {
            output.write(message);
            output.flush();
        }
    }

    private static SSLContext trustAllContext() throws Exception {
        // RDP servers usually present self-signed certificates. The client
        // binds CredSSP to the certificate chain we pass back, as with any
        // RDCleanPath gateway.
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { trustAll }, new SecureRandom());
        return context;
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("Connection closed");
            }
            if (value == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            if (line.length() > 8192) {
                throw new IOException("Header too long");
            }
            line.append((char) value);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length)
        throws IOException {
        while (length > 0) {
            int count = input.read(buffer, offset, length);
            if (count < 0) {
                throw new EOFException("Tunnel closed");
            }
            offset += count;
            length -= count;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    /** Presents the tunnel's WebSocket messages as a byte stream. */
    private static final class TunnelInputStream extends InputStream {
        private final AccessWebSocket tunnel;
        private byte[] current = new byte[0];
        private int position;
        private boolean ended;

        TunnelInputStream(AccessWebSocket tunnel) {
            this.tunnel = tunnel;
        }

        private boolean fill() throws IOException {
            while (position >= current.length) {
                if (ended) {
                    return false;
                }
                byte[] message = tunnel.readMessage();
                if (message == null) {
                    ended = true;
                    return false;
                }
                current = message;
                position = 0;
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            return fill() ? current[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, buffer, offset, count);
            position += count;
            return count;
        }
    }
}
