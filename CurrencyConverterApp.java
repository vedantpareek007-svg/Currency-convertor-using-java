import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Currency Converter web app.
 * Uses only the JDK: java.net.http.HttpClient for the external API call,
 * com.sun.net.httpserver for the web server. Requires Java 11+.
 *
 * Run:  java CurrencyConverterApp.java
 * Optional: set EXCHANGE_API_KEY to use https://v6.exchangerate-api.com (your key).
 * Without a key, the free open endpoint https://open.er-api.com is used.
 */
public class CurrencyConverterApp {

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static final String API_KEY = System.getenv("EXCHANGE_API_KEY");
    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    static final Path PUBLIC_DIR = Path.of("public");

    record Rates(String base, Map<String, Double> rates, String updated, Instant fetchedAt) {}

    static final Map<String, Rates> CACHE = new ConcurrentHashMap<>();

    // ---------- External API (Java built-in HTTP client) ----------

    static Rates getRates(String base) throws IOException, InterruptedException {
        Rates cached = CACHE.get(base);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached;
        }

        String url = (API_KEY != null && !API_KEY.isBlank())
                ? "https://v6.exchangerate-api.com/v6/" + API_KEY + "/latest/" + base
                : "https://open.er-api.com/v6/latest/" + base;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        if (response.statusCode() != 200 || !body.contains("\"success\"")) {
            throw new IOException("Exchange rate API error (HTTP " + response.statusCode() + ")");
        }

        Rates rates = parseRates(base, body);
        CACHE.put(base, rates);
        return rates;
    }

    /** Tiny JSON extraction so the app needs no libraries. */
    static Rates parseRates(String base, String json) throws IOException {
        Matcher block = Pattern.compile("\"(?:conversion_rates|rates)\"\\s*:\\s*\\{([^}]*)\\}").matcher(json);
        if (!block.find()) throw new IOException("Unexpected API response");

        Map<String, Double> map = new TreeMap<>();
        Matcher entry = Pattern.compile("\"([A-Z]{3})\"\\s*:\\s*([0-9.eE+-]+)").matcher(block.group(1));
        while (entry.find()) {
            map.put(entry.group(1), Double.parseDouble(entry.group(2)));
        }
        if (map.isEmpty()) throw new IOException("No rates in API response");

        Matcher upd = Pattern.compile("\"time_last_update_utc\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        String updated = upd.find() ? upd.group(1) : "";
        return new Rates(base, map, updated, Instant.now());
    }

    // ---------- HTTP handlers ----------

    static void handleCurrencies(HttpExchange ex) throws IOException {
        try {
            Rates r = getRates("USD");
            StringBuilder sb = new StringBuilder("{\"currencies\":[");
            boolean first = true;
            for (String code : r.rates().keySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(code).append('"');
                first = false;
            }
            sb.append("]}");
            sendJson(ex, 200, sb.toString());
        } catch (Exception e) {
            sendJson(ex, 502, error("Could not load currencies: " + e.getMessage()));
        }
    }

    static void handleConvert(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String from = q.getOrDefault("from", "").toUpperCase();
        String to = q.getOrDefault("to", "").toUpperCase();
        String amountStr = q.getOrDefault("amount", "");

        if (!from.matches("[A-Z]{3}") || !to.matches("[A-Z]{3}")) {
            sendJson(ex, 400, error("Invalid currency code"));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            sendJson(ex, 400, error("Amount must be a number"));
            return;
        }
        if (!Double.isFinite(amount) || amount < 0) {
            sendJson(ex, 400, error("Amount must be zero or greater"));
            return;
        }

        try {
            Rates supportedCurrencies = getRates("USD");
            if (!supportedCurrencies.rates().containsKey(from)) {
                sendJson(ex, 400, error("Unsupported currency: " + from));
                return;
            }
            Rates r = from.equals("USD") ? supportedCurrencies : getRates(from);
            Double rate = r.rates().get(to);
            if (rate == null) {
                sendJson(ex, 400, error("Unsupported currency: " + to));
                return;
            }
            double result = amount * rate;
            String json = String.format(Locale.ROOT,
                    "{\"from\":\"%s\",\"to\":\"%s\",\"amount\":%s,\"rate\":%s,\"result\":%s,\"updated\":\"%s\"}",
                    from, to, num(amount), num(rate), num(result), esc(r.updated()));
            sendJson(ex, 200, json);
        } catch (Exception e) {
            sendJson(ex, 502, error("Could not fetch exchange rates: " + e.getMessage()));
        }
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        Path file = PUBLIC_DIR.resolve(path.substring(1)).normalize();
        if (!file.startsWith(PUBLIC_DIR)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!Files.isRegularFile(file) && path.equals("/index.html")) {
            file = Path.of("index.html");
        }
        if (!Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    // ---------- Helpers ----------

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    static String num(double d) {
        return new java.math.BigDecimal(d).round(new java.math.MathContext(12)).stripTrailingZeros().toPlainString();
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String error(String msg) {
        return "{\"error\":\"" + esc(msg) + "\"}";
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/currencies", ex -> safely(ex, CurrencyConverterApp::handleCurrencies));
        server.createContext("/api/convert", ex -> safely(ex, CurrencyConverterApp::handleConvert));
        server.createContext("/", ex -> safely(ex, CurrencyConverterApp::handleStatic));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Currency Converter running at http://localhost:" + port);
    }

    interface Handler { void handle(HttpExchange ex) throws IOException; }

    static void safely(HttpExchange ex, Handler h) throws IOException {
        try {
            h.handle(ex);
        } catch (Exception e) {
            try { sendJson(ex, 500, error("Server error")); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }
}
