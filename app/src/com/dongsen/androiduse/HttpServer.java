package com.dongsen.androiduse;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简 HTTP/1.1 服务：每个连接只处理一个请求，响应后关闭。只为本 App 的 JSON 接口服务。 */
final class HttpServer {
    static final int MAX_BODY = 1024 * 1024;

    interface Handler {
        Response handle(Request request) throws Exception;
    }

    static final class Request {
        String method;
        String path;
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        InetAddress remote;
    }

    static final class Response {
        final int status;
        final String contentType;
        final byte[] body;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final int port;
    private final Handler handler;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    HttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    synchronized void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 16);
        pool = Executors.newFixedThreadPool(4);
        running = true;
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        }, "android-use-http");
        accept.setDaemon(true);
        accept.start();
    }

    synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
        server = null;
        pool = null;
    }

    boolean isRunning() {
        return running;
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                write(out, Response.json(400, "{\"ok\":false,\"error\":{\"code\":\"bad_request\",\"message\":\"Malformed request line\"}}"));
                return;
            }
            Request r = new Request();
            r.method = parts[0].toUpperCase(Locale.ROOT);
            r.path = parts[1];
            r.remote = s.getInetAddress();
            int headerBytes = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headerBytes += line.length();
                if (headerBytes > 16384) {
                    write(out, Response.json(431, "{\"ok\":false,\"error\":{\"code\":\"headers_too_large\",\"message\":\"Headers too large\"}}"));
                    return;
                }
                int i = line.indexOf(':');
                if (i > 0) r.headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            int length = 0;
            String cl = r.headers.get("content-length");
            if (cl != null) {
                try {
                    length = Integer.parseInt(cl);
                } catch (NumberFormatException e) {
                    length = -1;
                }
            }
            if (length < 0 || length > MAX_BODY) {
                write(out, Response.json(413, "{\"ok\":false,\"error\":{\"code\":\"body_too_large\",\"message\":\"Body too large\"}}"));
                return;
            }
            byte[] body = new byte[length];
            int off = 0;
            while (off < length) {
                int n = in.read(body, off, length - off);
                if (n < 0) break;
                off += n;
            }
            r.body = body;
            Response resp;
            try {
                resp = handler.handle(r);
            } catch (Exception e) {
                resp = Response.json(500, "{\"ok\":false,\"error\":{\"code\":\"internal_error\",\"message\":" + org.json.JSONObject.quote(String.valueOf(e)) + "}}");
            }
            write(out, resp);
        } catch (Exception ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(OutputStream out, Response r) throws IOException {
        String head = "HTTP/1.1 " + r.status + " " + reason(r.status) + "\r\n"
                + "Content-Type: " + r.contentType + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 431: return "Request Header Fields Too Large";
            default: return "Error";
        }
    }
}
