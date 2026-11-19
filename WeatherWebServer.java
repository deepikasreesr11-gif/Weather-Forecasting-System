import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class WeatherWebServer {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8080), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", WeatherWebServer::handle);
        server.start();
        System.out.println("Weather website listening at http://127.0.0.1:8080");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "GET requests only");
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                Path html = Path.of("index.html");
                if (!Files.isRegularFile(html)) {
                    send(exchange, 500, "text/plain; charset=utf-8",
                            "index.html is missing. Run the server from the weather API folder.");
                    return;
                }
                send(exchange, 200, "text/html; charset=utf-8",
                        Files.readAllBytes(html));
            } else if ("/api/geocoding".equals(path)) {
                String name = parameters(exchange).get("name");
                if (name == null || name.trim().isEmpty() || name.length() > 100) {
                    sendError(exchange, 400, "Enter a city name.");
                    return;
                }
                String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(name.trim(), StandardCharsets.UTF_8)
                        + "&count=1&language=en&format=json&countryCode=IN";
                proxy(exchange, url);
            } else if ("/api/forecast".equals(path)) {
                Map<String, String> params = parameters(exchange);
                double latitude = Double.parseDouble(params.get("latitude"));
                double longitude = Double.parseDouble(params.get("longitude"));
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                        || latitude < -90 || latitude > 90
                        || longitude < -180 || longitude > 180) {
                    sendError(exchange, 400, "Invalid location coordinates.");
                    return;
                }
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                        + "&longitude=" + longitude
                        + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                        + "weather_code,pressure_msl,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                        + "precipitation_probability_max,wind_speed_10m_max"
                        + "&forecast_days=5&timezone=auto";
                proxy(exchange, url);
            } else {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
            }
        } catch (NumberFormatException | NullPointerException ex) {
            sendError(exchange, 400, "Invalid or missing request parameters.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sendError(exchange, 502, "Weather request was interrupted. Please try again.");
        } catch (Exception ex) {
            sendError(exchange, 502, "Weather data could not be loaded: " + ex.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void proxy(HttpExchange exchange, String url)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "WeatherForecastingSystem/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            sendError(exchange, 502, "Weather service returned HTTP " + response.statusCode() + ".");
            return;
        }
        send(exchange, 200, "application/json; charset=utf-8", response.body());
    }

    private static Map<String, String> parameters(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        String json = "{\"error\":\"" + message.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", " ") + "\"}";
        send(exchange, status, "application/json; charset=utf-8", json);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
