package net.archcangyuan.codeserverapp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RFC 6455 WebSocket client for Cloudflare Access TCP tunnels, the same
 * transport {@code cloudflared access rdp} uses: a WebSocket to the application
 * hostname authenticated with the {@code Cf-Access-Token} header, carrying the
 * raw TCP stream as binary messages.
 */
final class AccessWebSocket implements AutoCloseable {
    /** Thrown when Access redirects to its login page: the token is missing or expired. */
    static final class LoginRequiredException extends IOException {
        LoginRequiredException(String message) {
            super(message);
        }
    }

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int OPCODE_CONTINUATION = 0x0;
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_BINARY = 0x2;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;
    private static final int OPCODE_PONG = 0xA;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private AccessWebSocket(Socket socket, InputStream input, OutputStream output) {
        this.socket = socket;
        this.input = input;
        this.output = output;
    }

    /** Opens {@code wss://host:443/} with the given Access token. */
    static AccessWebSocket connect(String host, String accessToken) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS);
            SSLSocket tlsSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(socket, host, 443, true);
            SSLParameters parameters = tlsSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tlsSocket.setSSLParameters(parameters);
            tlsSocket.startHandshake();
            return handshake(tlsSocket, host, accessToken);
        } catch (IOException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    /** Opens a plain {@code ws://} connection; used by tests against a local server. */
    static AccessWebSocket connectPlain(String host, int port, String accessToken)
        throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return handshake(socket, host + ":" + port, accessToken);
        } catch (IOException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    private static AccessWebSocket handshake(Socket socket, String hostHeader, String accessToken)
        throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(CONNECT_TIMEOUT_MS);
        InputStream input = new BufferedInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET / HTTP/1.1\r\n"
            + "Host: " + hostHeader + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Cf-Access-Token: " + accessToken + "\r\n"
            + "User-Agent: YourWorkspace\r\n"
            + "\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        String statusLine = readLine(input);
        String[] statusParts = statusLine.split(" ", 3);
        int status = statusParts.length >= 2 ? parseStatus(statusParts[1]) : -1;
        String accept = null;
        String location = null;
        for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (name.equals("sec-websocket-accept")) {
                accept = value;
            } else if (name.equals("location")) {
                location = value;
            }
        }

        if (status != 101) {
            boolean loginRedirect = (status == 302 || status == 303 || status == 307)
                && location != null
                && location.contains("/cdn-cgi/access/login");
            if (loginRedirect || status == 401 || status == 403) {
                throw new LoginRequiredException("Cloudflare Access sign-in required");
            }
            throw new IOException("Tunnel handshake failed: " + statusLine);
        }
        if (accept == null || !accept.equals(expectedAccept(key))) {
            throw new IOException("Tunnel handshake failed: invalid Sec-WebSocket-Accept");
        }
        socket.setSoTimeout(0);
        return new AccessWebSocket(socket, input, output);
    }

    private static int parseStatus(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String expectedAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("Connection closed during tunnel handshake");
            }
            if (value == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            if (line.length() > 8192) {
                throw new IOException("Tunnel handshake header too long");
            }
            line.append((char) value);
        }
    }

    /**
     * Checks the whole path to the remote desktop: sends an RDP X.224
     * Connection Request through the tunnel and waits for the Connection
     * Confirm. Returns a short human-readable result.
     */
    static String probeRemoteDesktop(String host, String accessToken) {
        // TPKT + X.224 Connection Request with RDP_NEG_REQ (TLS | CredSSP).
        byte[] request = {
            0x03, 0x00, 0x00, 0x13, 0x0e, (byte) 0xe0, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00, 0x00
        };
        try (AccessWebSocket tunnel = connect(host, accessToken)) {
            tunnel.setReadTimeout(10_000);
            tunnel.sendBinary(request, 0, request.length);
            byte[] response = tunnel.readMessage();
            if (response == null) {
                return "Tunnel opened, but the remote side closed it without answering. "
                    + "Check that the tunnel's service is rdp://… and the PC is reachable.";
            }
            if (response.length >= 6 && response[0] == 0x03 && (response[5] & 0xF0) == 0xD0) {
                return "OK: the remote desktop answered through Cloudflare.";
            }
            return "Tunnel opened, but the answer is not RDP (" + response.length + " bytes).";
        } catch (LoginRequiredException exception) {
            return "Cloudflare rejected the token: sign in again.";
        } catch (java.net.SocketTimeoutException exception) {
            return "Tunnel opened, but the remote desktop did not answer within 10 s.";
        } catch (IOException exception) {
            return "Failed: " + exception.getMessage();
        }
    }

    /** Sends one binary message. Safe to call from any thread. */
    void sendBinary(byte[] data, int offset, int length) throws IOException {
        sendFrame(OPCODE_BINARY, data, offset, length);
    }

    void sendPing() throws IOException {
        sendFrame(OPCODE_PING, new byte[0], 0, 0);
    }

    private void sendFrame(int opcode, byte[] data, int offset, int length) throws IOException {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        byte[] header;
        if (length < 126) {
            header = new byte[] { (byte) (0x80 | opcode), (byte) (0x80 | length) };
        } else if (length < 65_536) {
            header = new byte[] {
                (byte) (0x80 | opcode), (byte) (0x80 | 126),
                (byte) (length >>> 8), (byte) length
            };
        } else {
            header = new byte[10];
            header[0] = (byte) (0x80 | opcode);
            header[1] = (byte) (0x80 | 127);
            for (int index = 0; index < 8; index++) {
                header[2 + index] = (byte) (((long) length) >>> (56 - 8 * index));
            }
        }
        byte[] payload = new byte[length];
        for (int index = 0; index < length; index++) {
            payload[index] = (byte) (data[offset + index] ^ mask[index & 3]);
        }
        synchronized (writeLock) {
            output.write(header);
            output.write(mask);
            output.write(payload);
            output.flush();
        }
    }

    /**
     * Reads the next data message, answering pings along the way. Returns
     * {@code null} when the server closes the connection.
     */
    byte[] readMessage() throws IOException {
        ByteArrayOutputStream message = null;
        while (true) {
            int first = input.read();
            if (first < 0) {
                return null;
            }
            int second = readByte();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = ((long) readByte() << 8) | readByte();
            } else if (length == 127) {
                length = 0;
                for (int index = 0; index < 8; index++) {
                    length = (length << 8) | readByte();
                }
            }
            if (length > 64L * 1024 * 1024) {
                throw new IOException("Tunnel message too large");
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[(int) length];
            readFully(payload);
            if (mask != null) {
                for (int index = 0; index < payload.length; index++) {
                    payload[index] ^= mask[index & 3];
                }
            }

            switch (opcode) {
            case OPCODE_PING:
                sendFrame(OPCODE_PONG, payload, 0, payload.length);
                continue;
            case OPCODE_PONG:
                continue;
            case OPCODE_CLOSE:
                return null;
            case OPCODE_TEXT:
            case OPCODE_BINARY:
            case OPCODE_CONTINUATION:
                if (fin && message == null) {
                    return payload;
                }
                if (message == null) {
                    message = new ByteArrayOutputStream();
                }
                message.write(payload);
                if (fin) {
                    return message.toByteArray();
                }
                continue;
            default:
                throw new IOException("Unsupported tunnel frame opcode " + opcode);
            }
        }
    }

    private int readByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Tunnel closed mid-frame");
        }
        return value;
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                throw new EOFException("Tunnel closed mid-frame");
            }
            offset += count;
        }
    }

    /** Sets a read timeout in milliseconds for {@link #readMessage()}; 0 waits forever. */
    void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            synchronized (writeLock) {
                output.write(new byte[] { (byte) (0x80 | OPCODE_CLOSE), (byte) 0x80, 0, 0, 0, 0 });
                output.flush();
            }
        } catch (IOException ignored) {
            // The peer may already be gone.
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }
}
