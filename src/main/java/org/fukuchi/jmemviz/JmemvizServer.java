package org.fukuchi.jmemviz;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** JDK 標準の HttpServer で viewer と trace.json を localhost に配信し、ブラウザを開く。 */
final class JmemvizServer {

    private JmemvizServer() {}

    static void serve(Path tracePath, int port, boolean openBrowser) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/") || p.equals("/index.html")) {
                sendResource(ex, "viewer/index.html", "text/html; charset=utf-8");
            } else if (p.equals("/trace.json")) {
                sendFile(ex, tracePath, "application/json; charset=utf-8");
            } else {
                send(ex, 404, "text/plain", ("not found: " + p).getBytes());
            }
        });
        server.setExecutor(null);
        server.start();
        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("[jmemviz] serving viewer on " + url);
        System.out.println("[jmemviz] trace: " + tracePath.toAbsolutePath());
        System.out.println("[jmemviz] Ctrl+C to stop");
        if (openBrowser) tryOpen(url);
    }

    private static void sendResource(HttpExchange ex, String classpath, String contentType) throws IOException {
        try (InputStream in = JmemvizServer.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                send(ex, 500, "text/plain", ("resource not found: " + classpath).getBytes());
                return;
            }
            send(ex, 200, contentType, in.readAllBytes());
        }
    }

    private static void sendFile(HttpExchange ex, Path file, String contentType) throws IOException {
        if (!Files.exists(file)) {
            send(ex, 404, "text/plain", ("trace file not found: " + file).getBytes());
            return;
        }
        send(ex, 200, contentType, Files.readAllBytes(file));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    private static void tryOpen(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Throwable ignored) {}
        // Fallback: try xdg-open on Linux
        try {
            new ProcessBuilder("xdg-open", url).inheritIO().start();
        } catch (IOException e) {
            System.out.println("[jmemviz] could not auto-open browser, please open " + url + " manually");
        }
    }
}
