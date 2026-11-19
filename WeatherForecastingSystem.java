import java.awt.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/*
 * Weather Forecasting System - live data for ANY location in India
 * API : Open-Meteo (free, no API key needed)
 *       https://open-meteo.com
 * Needs Java 11 or above. No external library needed.
 *
 * Compile : javac WeatherForecastingSystem.java
 * Run     : java WeatherForecastingSystem
 */
public class WeatherForecastingSystem extends JFrame {

    static final String DEG = "\u00B0C";

    CardLayout cardLayout = new CardLayout();
    JPanel pages = new JPanel(cardLayout);
    JPanel mainCards;

    JPanel dashBody = new JPanel(new BorderLayout(10, 10));
    JPanel locBody = new JPanel(new BorderLayout(10, 10));
    JPanel foreBody = new JPanel(new BorderLayout(10, 10));
    JPanel alertBody = new JPanel(new BorderLayout(10, 10));

    JTextField searchField = new JTextField(18);
    JButton searchBtn;
    JLabel statusLabel = new JLabel(" ");

    Weather current;
    String lastQuery = null;
    JLabel clockLabel = new JLabel(" ");
    javax.swing.Timer clockTimer, refreshTimer;
    static final int REFRESH_MINUTES = 5; // auto-refresh interval
    final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    Color BLUE = new Color(20, 82, 165);
    Color LIGHT_BLUE = new Color(42, 124, 210);
    Color BG = new Color(245, 248, 252);

    // ---------------------------------------------------------------- MODEL

    static class Weather {
        String city, condition, localTime;
        double lat, lon;
        int temp, feelsLike, humidity, rain, wind, pressure;
        double visibility;
        // 5 day forecast
        String[] day = new String[5];
        String[] dCond = new String[5];
        int[] dMax = new int[5], dMin = new int[5], dRain = new int[5], dWind = new int[5];
        int[] dCode = new int[5];
        int code;
        int utcOffset; // seconds, used for the live clock
    }

    // ------------------------------------------------------------ CONSTRUCTOR

    public WeatherForecastingSystem() {
        setTitle("Weather Forecasting System");
        setSize(1200, 750);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        pages.add(loginPage(), "LOGIN");
        pages.add(mainPage(), "MAIN");
        setContentPane(pages);
        showPage("LOGIN");
        startTimers();
    }

    // ------------------------------------------------------- REAL-TIME TIMERS

    void startTimers() {
        // live clock - ticks every second (time of the searched city)
        clockTimer = new javax.swing.Timer(1000, e -> updateClock());
        clockTimer.start();

        // auto refresh - fetches fresh weather every few minutes
        refreshTimer = new javax.swing.Timer(REFRESH_MINUTES * 60 * 1000, e -> {
            if (lastQuery != null) searchWeather(lastQuery, true);
        });
        refreshTimer.start();
    }

    void updateClock() {
        if (current == null) {
            clockLabel.setText(" ");
            return;
        }
        java.time.ZonedDateTime t = java.time.ZonedDateTime.now(
                java.time.ZoneOffset.ofTotalSeconds(current.utcOffset));
        clockLabel.setText(t.format(java.time.format.DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy  hh:mm:ss a", Locale.ENGLISH)));
    }

    // ---------------------------------------------------------------- HELPERS

    JLabel label(String s, int size, Color c, int style) {
        JLabel l = new JLabel(s);
        l.setFont(new Font("Arial", style, size));
        l.setForeground(c);
        return l;
    }

    JButton button(String s) {
        JButton b = new JButton(s);
        b.setBackground(BLUE);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        return b;
    }

    void showPage(String s) {
        cardLayout.show(pages, s);
    }

    void showMainCard(String name) {
        ((CardLayout) mainCards.getLayout()).show(mainCards, name);
    }

    // ------------------------------------------------------------------ LOGIN

    JPanel loginPage() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG);

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(Color.WHITE);
        box.setBorder(new EmptyBorder(40, 50, 40, 50));

        JLabel title = label("WEATHER FORECASTING SYSTEM", 22, BLUE, Font.BOLD);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        box.add(title);
        box.add(Box.createVerticalStrut(30));

        box.add(label("Username", 14, Color.BLACK, Font.BOLD));
        JTextField user = new JTextField(20);
        box.add(user);

        box.add(Box.createVerticalStrut(15));
        box.add(label("Password", 14, Color.BLACK, Font.BOLD));
        JPasswordField pass = new JPasswordField(20);
        box.add(pass);

        box.add(Box.createVerticalStrut(20));
        JButton login = button("LOGIN");
        login.setAlignmentX(Component.CENTER_ALIGNMENT);
        box.add(login);

        box.add(Box.createVerticalStrut(15));
        JLabel hint = label("Demo: admin / 1234", 12, Color.GRAY, Font.PLAIN);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        box.add(hint);

        login.addActionListener(e -> {
            if (user.getText().equals("admin")
                    && new String(pass.getPassword()).equals("1234")) {
                showPage("MAIN");
                showMainCard("DASHBOARD");
                if (current == null) searchWeather("Coimbatore"); // default city
            } else {
                JOptionPane.showMessageDialog(this, "Invalid Login!");
            }
        });

        root.add(box);
        return root;
    }

    // ------------------------------------------------------------ MAIN LAYOUT

    JPanel mainPage() {
        JPanel root = new JPanel(new BorderLayout());
        root.add(sidebar(), BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(BG);
        right.add(searchBar(), BorderLayout.NORTH);

        mainCards = new JPanel(new CardLayout());
        mainCards.add(wrap(dashBody), "DASHBOARD");
        mainCards.add(wrap(locBody), "LOCATION");
        mainCards.add(wrap(foreBody), "FORECAST");
        mainCards.add(wrap(alertBody), "ALERTS");

        right.add(mainCards, BorderLayout.CENTER);
        root.add(right, BorderLayout.CENTER);
        return root;
    }

    JPanel wrap(JPanel body) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(10, 20, 20, 20));
        body.setBackground(BG);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    JPanel searchBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG);
        bar.setBorder(new EmptyBorder(15, 20, 5, 20));

        bar.add(label("Weather Dashboard", 28, BLUE, Font.BOLD), BorderLayout.WEST);

        JPanel s = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        s.setBackground(BG);
        searchField.setFont(new Font("Arial", Font.PLAIN, 15));
        searchBtn = button("Search");
        s.add(label("City / Town:", 14, Color.BLACK, Font.BOLD));
        s.add(searchField);
        s.add(searchBtn);
        JButton refreshBtn = button("Refresh Now");
        refreshBtn.addActionListener(e -> {
            if (lastQuery != null) searchWeather(lastQuery, false);
        });
        s.add(refreshBtn);
        bar.add(s, BorderLayout.EAST);

        statusLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        statusLabel.setForeground(Color.GRAY);
        clockLabel.setFont(new Font("Arial", Font.BOLD, 13));
        clockLabel.setForeground(BLUE);
        JPanel info = new JPanel(new BorderLayout());
        info.setBackground(BG);
        info.add(statusLabel, BorderLayout.WEST);
        info.add(clockLabel, BorderLayout.EAST);
        bar.add(info, BorderLayout.SOUTH);

        searchBtn.addActionListener(e -> searchWeather(searchField.getText().trim()));
        searchField.addActionListener(e -> searchWeather(searchField.getText().trim()));
        return bar;
    }

    JPanel sidebar() {
        JPanel side = new JPanel();
        side.setPreferredSize(new Dimension(200, 0));
        side.setBackground(BLUE);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(new EmptyBorder(25, 15, 20, 15));

        JLabel logo = label("WEATHER", 23, Color.WHITE, Font.BOLD);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(logo);
        side.add(Box.createVerticalStrut(40));

        String[] names = {"Dashboard", "Location", "Forecast", "Alerts", "Logout"};
        String[] keys = {"DASHBOARD", "LOCATION", "FORECAST", "ALERTS", "LOGIN"};

        for (int i = 0; i < names.length; i++) {
            String key = keys[i];
            JButton b = button(names[i]);
            b.setMaximumSize(new Dimension(170, 40));
            side.add(b);
            side.add(Box.createVerticalStrut(15));
            b.addActionListener(e -> {
                if (key.equals("LOGIN")) showPage("LOGIN");
                else showMainCard(key);
            });
        }
        return side;
    }

    // ------------------------------------------------------------ SEARCH LOGIC

    void searchWeather(String query) {
        searchWeather(query, false);
    }

    // silent = true -> background auto refresh (no pop-ups, stays on the same page)
    void searchWeather(String query, boolean silent) {
        if (query == null || query.isEmpty()) {
            if (!silent) JOptionPane.showMessageDialog(this, "Please enter a city or town name.");
            return;
        }
        searchBtn.setEnabled(false);
        statusLabel.setText((silent ? "Auto refreshing " : "Loading weather for ") + "\"" + query + "\" ...");

        new SwingWorker<Weather, Void>() {
            String error = null;

            @Override
            protected Weather doInBackground() {
                try {
                    return fetchWeather(query);
                } catch (Exception ex) {
                    error = ex.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                searchBtn.setEnabled(true);
                try {
                    Weather w = get();
                    if (error != null) {
                        statusLabel.setText("Error");
                        if (!silent) JOptionPane.showMessageDialog(WeatherForecastingSystem.this,
                                "Could not get weather data.\nCheck your internet connection.\n\n" + error);
                    } else if (w == null) {
                        statusLabel.setText("Location not found");
                        if (!silent) JOptionPane.showMessageDialog(WeatherForecastingSystem.this,
                                "Location not found in India!\n\nCheck the spelling, e.g. Karur, Musiri, Trichy.");
                    } else {
                        current = w;
                        lastQuery = query;
                        statusLabel.setText("Live data | updated " + w.localTime.replace('T', ' ')
                                + " | auto-refresh every " + REFRESH_MINUTES + " min");
                        updateClock();
                        refreshPages();
                        if (!silent) showMainCard("DASHBOARD");
                    }
                } catch (Exception ex) {
                    statusLabel.setText("Error");
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------- API CALLS

    String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "WeatherForecastingSystem/1.0")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200)
            throw new RuntimeException("HTTP " + res.statusCode() + " from " + url);
        return res.body();
    }

    Weather fetchWeather(String query) throws Exception {
        // 1) Geocoding - city name -> latitude / longitude (India only)
        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=1&language=en&format=json&countryCode=IN";
        Map<String, Object> geo = map(Json.parse(httpGet(geoUrl)));
        List<Object> results = list(geo.get("results"));
        if (results == null || results.isEmpty()) return null;

        Map<String, Object> place = map(results.get(0));
        Weather w = new Weather();
        w.lat = num(place.get("latitude"));
        w.lon = num(place.get("longitude"));
        String name = String.valueOf(place.get("name"));
        Object state = place.get("admin1");
        w.city = state == null ? name : name + ", " + state;

        // 2) Forecast - current + 5 days
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + w.lat + "&longitude=" + w.lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,"
                + "pressure_msl,wind_speed_10m"
                + "&hourly=visibility"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "precipitation_probability_max,wind_speed_10m_max"
                + "&forecast_days=5&timezone=auto";
        Map<String, Object> f = map(Json.parse(httpGet(url)));

        Map<String, Object> cur = map(f.get("current"));
        w.temp = (int) Math.round(num(cur.get("temperature_2m")));
        w.feelsLike = (int) Math.round(num(cur.get("apparent_temperature")));
        w.humidity = (int) Math.round(num(cur.get("relative_humidity_2m")));
        w.pressure = (int) Math.round(num(cur.get("pressure_msl")));
        w.wind = (int) Math.round(num(cur.get("wind_speed_10m")));
        w.code = (int) num(cur.get("weather_code"));
        w.condition = describe(w.code);
        w.localTime = String.valueOf(cur.get("time"));
        w.utcOffset = (int) num(f.get("utc_offset_seconds"));

        // visibility of current hour (hourly list starts at 00:00 today)
        try {
            int hour = Integer.parseInt(w.localTime.substring(11, 13));
            List<Object> vis = list(map(f.get("hourly")).get("visibility"));
            w.visibility = Math.round(num(vis.get(hour)) / 100.0) / 10.0; // metres -> km
        } catch (Exception ignore) {
            w.visibility = 0;
        }

        Map<String, Object> d = map(f.get("daily"));
        List<Object> dates = list(d.get("time"));
        List<Object> codes = list(d.get("weather_code"));
        List<Object> mx = list(d.get("temperature_2m_max"));
        List<Object> mn = list(d.get("temperature_2m_min"));
        List<Object> rn = list(d.get("precipitation_probability_max"));
        List<Object> wn = list(d.get("wind_speed_10m_max"));

        int n = Math.min(5, dates.size());
        for (int i = 0; i < n; i++) {
            w.day[i] = LocalDate.parse(String.valueOf(dates.get(i)))
                    .getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            w.dCode[i] = (int) num(codes.get(i));
            w.dCond[i] = describe(w.dCode[i]);
            w.dMax[i] = (int) Math.round(num(mx.get(i)));
            w.dMin[i] = (int) Math.round(num(mn.get(i)));
            w.dRain[i] = (int) Math.round(num(rn.get(i)));
            w.dWind[i] = (int) Math.round(num(wn.get(i)));
        }
        w.rain = w.dRain[0]; // today's chance of rain
        return w;
    }

    // WMO weather code -> text
    static String describe(int c) {
        if (c == 0) return "Clear Sky";
        if (c == 1) return "Mainly Clear";
        if (c == 2) return "Partly Cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Foggy";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rainy";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Rain Showers";
        if (c == 85 || c == 86) return "Snow Showers";
        if (c >= 95) return "Thunderstorm";
        return "Unknown";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    // ------------------------------------------------------------ UI REFRESH

    void refreshPages() {
        buildDashboard();
        buildLocation();
        buildForecast();
        buildAlerts();
    }

    void reset(JPanel p) {
        p.removeAll();
    }

    void done(JPanel p) {
        p.revalidate();
        p.repaint();
    }

    JPanel statCard(String title, String value) {
        JPanel c = new JPanel(new BorderLayout());
        c.setBackground(Color.WHITE);
        c.setBorder(new EmptyBorder(15, 10, 15, 10));
        JLabel t = label(title, 14, Color.GRAY, Font.PLAIN);
        t.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel v = label(value, 22, LIGHT_BLUE, Font.BOLD);
        v.setHorizontalAlignment(SwingConstants.CENTER);
        c.add(t, BorderLayout.NORTH);
        c.add(v, BorderLayout.CENTER);
        return c;
    }

    JPanel dayCard(Weather w, int i) {
        JPanel c = new JPanel(new GridLayout(0, 1, 2, 2));
        c.setBackground(Color.WHITE);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 220, 235)),
                new EmptyBorder(8, 8, 8, 8)));
        JLabel d = label(w.day[i], 15, Color.BLACK, Font.BOLD);
        JLabel t = label(w.dMax[i] + DEG + " / " + w.dMin[i] + DEG, 18, LIGHT_BLUE, Font.BOLD);
        JLabel cd = label(w.dCond[i], 13, Color.BLACK, Font.PLAIN);
        JLabel r = label("Rain " + w.dRain[i] + " %", 12, Color.GRAY, Font.PLAIN);
        JLabel wd = label("Wind " + w.dWind[i] + " km/h", 12, Color.GRAY, Font.PLAIN);
        for (JLabel l : new JLabel[]{d, t, cd, r, wd}) {
            l.setHorizontalAlignment(SwingConstants.CENTER);
            c.add(l);
        }
        return c;
    }

    // ---- DASHBOARD
    void buildDashboard() {
        reset(dashBody);
        Weather w = current;

        JPanel banner = new JPanel(new GridLayout(0, 1, 3, 3));
        banner.setBackground(LIGHT_BLUE);
        banner.setBorder(new EmptyBorder(18, 25, 18, 25));
        banner.add(label(w.city, 30, Color.WHITE, Font.BOLD));
        banner.add(label(w.temp + DEG, 54, Color.WHITE, Font.BOLD));
        banner.add(label(w.condition + "   (Feels like " + w.feelsLike + DEG + ")", 20, Color.WHITE, Font.BOLD));
        dashBody.add(banner, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(5, 5));
        center.setBackground(BG);
        center.add(label("Today's Weather Details", 22, BLUE, Font.BOLD), BorderLayout.NORTH);

        JPanel stats = new JPanel(new GridLayout(1, 6, 12, 12));
        stats.setBackground(BG);
        stats.add(statCard("Temperature", w.temp + DEG));
        stats.add(statCard("Humidity", w.humidity + " %"));
        stats.add(statCard("Rain Chance", w.rain + " %"));
        stats.add(statCard("Wind Speed", w.wind + " km/h"));
        stats.add(statCard("Pressure", w.pressure + " hPa"));
        stats.add(statCard("Visibility", w.visibility + " km"));
        center.add(stats, BorderLayout.CENTER);
        dashBody.add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(5, 5));
        south.setBackground(BG);
        JLabel t = label("5-Day Forecast", 22, BLUE, Font.BOLD);
        south.add(t, BorderLayout.NORTH);
        south.add(forecastRow(w), BorderLayout.CENTER);
        south.setPreferredSize(new Dimension(0, 190));
        dashBody.add(south, BorderLayout.SOUTH);
        done(dashBody);
    }

    JPanel forecastRow(Weather w) {
        JPanel row = new JPanel(new GridLayout(1, 5, 10, 10));
        row.setBackground(BG);
        for (int i = 0; i < 5; i++) {
            if (w.day[i] != null) row.add(dayCard(w, i));
        }
        return row;
    }

    // ---- LOCATION
    void buildLocation() {
        reset(locBody);
        Weather w = current;
        locBody.add(label("Location Details", 26, BLUE, Font.BOLD), BorderLayout.NORTH);

        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font("Arial", Font.PLAIN, 18));
        area.setBorder(new EmptyBorder(15, 15, 15, 15));
        area.setText(
                "Location      : " + w.city +
                "\nLatitude      : " + w.lat +
                "\nLongitude     : " + w.lon +
                "\nLocal Time    : " + w.localTime.replace('T', ' ') +
                "\n" +
                "\nTemperature   : " + w.temp + " " + DEG +
                "\nFeels Like    : " + w.feelsLike + " " + DEG +
                "\nCondition     : " + w.condition +
                "\nHumidity      : " + w.humidity + " %" +
                "\nRain Chance   : " + w.rain + " %" +
                "\nWind          : " + w.wind + " km/h" +
                "\nPressure      : " + w.pressure + " hPa" +
                "\nVisibility    : " + w.visibility + " km");
        locBody.add(new JScrollPane(area), BorderLayout.CENTER);
        done(locBody);
    }

    // ---- 5 DAY FORECAST
    void buildForecast() {
        reset(foreBody);
        Weather w = current;
        foreBody.add(label("5-Day Weather Forecast - " + w.city, 25, BLUE, Font.BOLD), BorderLayout.NORTH);

        JPanel row = forecastRow(w);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(BG);
        row.setPreferredSize(new Dimension(0, 260));
        holder.add(row, BorderLayout.NORTH);
        foreBody.add(holder, BorderLayout.CENTER);
        done(foreBody);
    }

    // ---- ALERTS
    void buildAlerts() {
        reset(alertBody);
        Weather w = current;
        alertBody.add(label("Weather Alerts - " + w.city, 26, BLUE, Font.BOLD), BorderLayout.NORTH);

        JPanel alerts = new JPanel(new GridLayout(0, 1, 10, 10));
        alerts.setBackground(BG);

        for (int i = 0; i < 5; i++) {
            if (w.day[i] == null) continue;
            JPanel card = new JPanel(new GridLayout(0, 1, 5, 5));
            card.setBackground(Color.WHITE);
            card.setBorder(new EmptyBorder(15, 15, 15, 15));
            card.add(label(w.day[i] + (i == 0 ? " (Today)" : ""), 18, BLUE, Font.BOLD));

            String msg;
            if (w.dCode[i] >= 95)
                msg = "Thunderstorm Alert! Stay indoors.";
            else if (w.dRain[i] >= 60)
                msg = "Heavy Rain Alert! Carry an umbrella.";
            else if (w.dMax[i] >= 38)
                msg = "Extreme Heat Alert! Avoid going out in the afternoon.";
            else if (w.dMax[i] >= 35)
                msg = "High Temperature Alert! Stay hydrated.";
            else if (w.dWind[i] >= 30)
                msg = "High Wind Alert! Be careful outdoors.";
            else
                msg = "No Severe Weather Alert.";

            card.add(label(msg, 15, Color.BLACK, Font.PLAIN));
            alerts.add(card);
        }

        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(BG);
        holder.add(alerts, BorderLayout.NORTH);
        alertBody.add(new JScrollPane(holder), BorderLayout.CENTER);
        done(alertBody);
    }

    // ------------------------------------------------- SMALL JSON PARSER
    // (so no external library / jar is needed)

    static class Json {
        private final String s;
        private int i = 0;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            return new Json(s).value();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new HashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    i++; // ':'
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') break;
                }
                return m;
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') break;
                }
                return l;
            }
            if (c == '"') return str();
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        private String str() {
            i++; // opening quote
            StringBuilder b = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            i++; // closing quote
            return b.toString();
        }
    }

    // ------------------------------------------------------------------ MAIN

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new WeatherForecastingSystem().setVisible(true));
    }
}