package dev.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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
        server.createContext("/api/start", exchange -> start(exchange, engine));
        server.createContext("/api/control", exchange -> control(exchange, engine));
        server.createContext("/api/stop", exchange -> stop(exchange, engine));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { engine.stop(); } catch (Exception ignored) {}
            server.stop(0);
        }));
        server.start();
        System.out.println("Interface disponível em http://localhost:8080");
        System.out.println("Pressione Ctrl+C para encerrar.");
    }

    private static void home(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "text/plain; charset=utf-8", "Não encontrado");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", new String(PAGE, StandardCharsets.UTF_8));
    }

    private static void start(HttpExchange exchange, CsoundEngine engine) throws IOException {
        if (!requirePost(exchange)) return;
        try {
            String waveform = parameter(exchange, "waveform", "sine");
            double frequency = numberParameter(exchange, "frequency", 440);
            double volume = numberParameter(exchange, "volume", 0.2);
            double phase = numberParameter(exchange, "phase", 0);
            engine.start(waveform, frequency, volume, phase);
            sendJson(exchange, 200, "{\"message\":\"Csound iniciado\"}");
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, "{\"error\":\"Parâmetros inválidos\"}");
        } catch (IllegalStateException error) {
            sendJson(exchange, 409, "{\"error\":\"" + escape(error.getMessage()) + "\"}");
        } catch (Throwable error) {
            error.printStackTrace();
            sendJson(exchange, 500, "{\"error\":\"Não foi possível iniciar o Csound\"}");
        }
    }

    private static void stop(HttpExchange exchange, CsoundEngine engine) throws IOException {
        if (!requirePost(exchange)) return;
        try {
            engine.stop();
            sendJson(exchange, 200, "{\"message\":\"Csound parado\"}");
        } catch (IllegalStateException error) {
            sendJson(exchange, 409, "{\"error\":\"" + escape(error.getMessage()) + "\"}");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 500, "{\"error\":\"Parada interrompida\"}");
        }
    }

    private static void control(HttpExchange exchange, CsoundEngine engine) throws IOException {
        if (!requirePost(exchange)) return;
        try {
            double frequency = numberParameter(exchange, "frequency", 440);
            double volume = numberParameter(exchange, "volume", 0.2);
            double phase = numberParameter(exchange, "phase", 0);
            engine.update(frequency, volume, phase);
            sendJson(exchange, 200, "{\"message\":\"Controles atualizados\"}");
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, "{\"error\":\"Parâmetros inválidos\"}");
        } catch (IllegalStateException error) {
            sendJson(exchange, 409, "{\"error\":\"" + escape(error.getMessage()) + "\"}");
        } catch (Throwable error) {
            error.printStackTrace();
            sendJson(exchange, 500, "{\"error\":\"Não foi possível atualizar o Csound\"}");
        }
    }

    private static boolean requirePost(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("POST")) return true;
        exchange.getResponseHeaders().set("Allow", "POST");
        sendJson(exchange, 405, "{\"error\":\"Use POST\"}");
        return false;
    }

    private static double numberParameter(HttpExchange exchange, String name, double defaultValue) {
        return Double.parseDouble(parameter(exchange, name, Double.toString(defaultValue)));
    }

    private static String parameter(HttpExchange exchange, String name, String defaultValue) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return defaultValue;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            }
        }
        return defaultValue;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) { response.write(bytes); }
    }

    private static byte[] resource(String name) {
        try (InputStream input = Main.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Recurso não encontrado: " + name);
            return input.readAllBytes();
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
