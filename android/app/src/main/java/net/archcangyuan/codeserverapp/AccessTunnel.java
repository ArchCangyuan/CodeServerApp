package net.archcangyuan.codeserverapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Local TCP listener that forwards each accepted connection through its own
 * Cloudflare Access WebSocket, like {@code cloudflared access rdp --url}.
 * WebSocket pings keep idle connections from being dropped by NATs and
 * proxies without injecting any input into the remote session.
 */
final class AccessTunnel implements AutoCloseable {
    interface Connector {
        AccessWebSocket open() throws IOException;
    }

    interface Listener {
        void onConnectionsChanged(int activeConnections);

        void onLoginRequired();

        void onError(String message);
    }

    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long PING_INTERVAL_SECONDS = 20;

    private final ServerSocket server;
    private final Connector connector;
    private final Listener listener;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Set<AccessWebSocket> tunnels = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;

    private AccessTunnel(ServerSocket server, Connector connector, Listener listener) {
        this.server = server;
        this.connector = connector;
        this.listener = listener;
    }

    /**
     * Binds the first free loopback port from {@code preferredPort} upwards
     * (trying {@code attempts} ports) and starts accepting connections.
     */
    static AccessTunnel start(
        int preferredPort,
        int attempts,
        Connector connector,
        Listener listener
    ) throws IOException {
        IOException lastFailure = null;
        for (int offset = 0; offset < attempts; offset++) {
            ServerSocket server = new ServerSocket();
            try {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), preferredPort + offset));
                AccessTunnel tunnel = new AccessTunnel(server, connector, listener);
                tunnel.startThreads();
                return tunnel;
            } catch (IOException exception) {
                server.close();
                lastFailure = exception;
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("No free local port");
    }

    int port() {
        return server.getLocalPort();
    }

    int activeConnections() {
        return clients.size();
    }

    private void startThreads() {
        Thread acceptThread = new Thread(this::acceptLoop, "AccessTunnel-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        pinger.scheduleWithFixedDelay(() -> {
            for (AccessWebSocket tunnel : tunnels) {
                try {
                    tunnel.sendPing();
                } catch (IOException ignored) {
                    // The reader thread notices the broken connection and cleans up.
                }
            }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException exception) {
                if (!closed) {
                    listener.onError(exception.getMessage());
                }
                return;
            }
            Thread handler = new Thread(() -> handle(client), "AccessTunnel-connect");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handle(Socket client) {
        AccessWebSocket tunnel;
        try {
            client.setTcpNoDelay(true);
            tunnel = connector.open();
        } catch (AccessWebSocket.LoginRequiredException exception) {
            closeQuietly(client);
            listener.onLoginRequired();
            return;
        } catch (IOException exception) {
            closeQuietly(client);
            listener.onError(exception.getMessage());
            return;
        }
        if (closed) {
            tunnel.close();
            closeQuietly(client);
            return;
        }
        clients.add(client);
        tunnels.add(tunnel);
        listener.onConnectionsChanged(clients.size());

        Runnable finish = () -> {
            tunnel.close();
            closeQuietly(client);
            boolean removed = clients.remove(client);
            tunnels.remove(tunnel);
            if (removed) {
                listener.onConnectionsChanged(clients.size());
            }
        };

        Thread upstream = new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = client.getInputStream()) {
                for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                    if (count > 0) {
                        tunnel.sendBinary(buffer, 0, count);
                    }
                }
            } catch (IOException ignored) {
                // Either side closed; finish below.
            } finally {
                finish.run();
            }
        }, "AccessTunnel-up");
        Thread downstream = new Thread(() -> {
            try (OutputStream output = client.getOutputStream()) {
                for (byte[] message = tunnel.readMessage();
                    message != null;
                    message = tunnel.readMessage()) {
                    output.write(message);
                    output.flush();
                }
            } catch (IOException ignored) {
                // Either side closed; finish below.
            } finally {
                finish.run();
            }
        }, "AccessTunnel-down");
        upstream.setDaemon(true);
        downstream.setDaemon(true);
        upstream.start();
        downstream.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    @Override
    public void close() {
        closed = true;
        pinger.shutdownNow();
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        for (AccessWebSocket tunnel : tunnels) {
            tunnel.close();
        }
        for (Socket client : clients) {
            closeQuietly(client);
        }
        tunnels.clear();
        clients.clear();
    }
}
