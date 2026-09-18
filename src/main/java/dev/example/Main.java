package dev.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Servidor HTTP local da interface do Csound. */
public final class Main {
    private static final byte[] PAGE = resource("/index.html");
    private Main() {}

    public static void main(String[] args) throws IOException {
        var engine = new CsoundEngine();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", Main::home);
        server.createContext("/api/play", exchange -> play(exchange, engine));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("Interface disponível em http://localhost:8080");
        System.out.println("Pressione Ctrl+C para encerrar.");
    }

    private static void home(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "text/plain; charset=utf-8", bytes("Não encontrado"));
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", PAGE);
    }

    private static void play(HttpExchange exchange, CsoundEngine engine) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "application/json; charset=utf-8", bytes("{\"error\":\"Use POST\"}"));
            return;
        }
        try {
            engine.play440Hz();
            send(exchange, 200, "application/json; charset=utf-8",
                    bytes("{\"message\":\"Senoide de 440 Hz concluída\"}"));
        } catch (Throwable error) {
            error.printStackTrace();
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            String json = "{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            send(exchange, 500, "application/json; charset=utf-8", bytes(json));
        }
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var response = exchange.getResponseBody()) { response.write(body); }
    }

    private static byte[] resource(String name) {
        try (InputStream input = Main.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Recurso não encontrado: " + name);
            return input.readAllBytes();
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
