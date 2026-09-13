package dev.suven.jungey.skills;

import com.fasterxml.jackson.databind.JsonNode;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.net.Http;

import java.util.Map;

/**
 * Live weather from Open-Meteo, which needs no API key.
 *
 * <p>Location comes from the config file if set, otherwise from a coarse IP lookup,
 * which is accurate to the city and good enough for a forecast.
 */
public class WeatherSkill implements Skill {

    /** WMO weather codes, as returned by Open-Meteo. */
    private static final Map<Integer, String> CODES = Map.ofEntries(
            Map.entry(0, "clear sky"), Map.entry(1, "mainly clear"),
            Map.entry(2, "partly cloudy"), Map.entry(3, "overcast"),
            Map.entry(45, "fog"), Map.entry(48, "freezing fog"),
            Map.entry(51, "light drizzle"), Map.entry(53, "drizzle"), Map.entry(55, "heavy drizzle"),
            Map.entry(61, "light rain"), Map.entry(63, "rain"), Map.entry(65, "heavy rain"),
            Map.entry(71, "light snow"), Map.entry(73, "snow"), Map.entry(75, "heavy snow"),
            Map.entry(80, "rain showers"), Map.entry(81, "rain showers"), Map.entry(82, "violent rain showers"),
            Map.entry(95, "a thunderstorm"), Map.entry(96, "a thunderstorm with hail"),
            Map.entry(99, "a severe thunderstorm with hail")
    );

    private String cachedCity;
    private double[] cachedCoords;

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "Current conditions and today's forecast, anywhere.";
    }

    @Override
    public String[] examples() {
        return new String[]{"weather", "what's the weather in Tokyo", "will it rain today"};
    }

    @Override
    public int priority() {
        return 200;   // network-bound
    }

    @Override
    public boolean matches(String input) {
        return input.matches(".*\\b(weather|forecast|temperature|raining|rain|snow|humid|hot|cold outside)\\b.*");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String place = extractPlace(input);

        double lat, lon;
        String label;

        if (place != null) {
            JsonNode geo = Http.getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + Http.enc(place));
            JsonNode results = geo.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return SkillResult.error("I could not find a place called \"" + place + "\".");
            }
            JsonNode hit = results.get(0);
            lat = hit.path("latitude").asDouble();
            lon = hit.path("longitude").asDouble();
            label = hit.path("name").asText();
            String country = hit.path("country").asText("");
            if (!country.isBlank()) label += ", " + country;
        } else {
            double[] coords = hereCoords();
            lat = coords[0];
            lon = coords[1];
            label = cachedCity != null ? cachedCity : "your location";
        }

        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=2";

        JsonNode w = Http.getJson(url);
        JsonNode cur = w.path("current");
        JsonNode daily = w.path("daily");

        double temp = cur.path("temperature_2m").asDouble();
        double feels = cur.path("apparent_temperature").asDouble();
        int humidity = cur.path("relative_humidity_2m").asInt();
        double wind = cur.path("wind_speed_10m").asDouble();
        int code = cur.path("weather_code").asInt();
        String condition = CODES.getOrDefault(code, "unsettled weather");

        double hi = daily.path("temperature_2m_max").path(0).asDouble();
        double lo = daily.path("temperature_2m_min").path(0).asDouble();
        int pop = daily.path("precipitation_probability_max").path(0).asInt();

        String spoken = String.format(
                "%s in %s, %.0f degrees, feels like %.0f. High of %.0f, low of %.0f%s.",
                capitalise(condition), label, temp, feels, hi, lo,
                pop >= 40 ? String.format(", with a %d percent chance of precipitation", pop) : "");

        String detail = String.format("""
                        LOCATION     %s
                        CONDITION    %s
                        NOW          %.1f C   (feels %.1f C)
                        TODAY        high %.1f C  /  low %.1f C
                        HUMIDITY     %d%%
                        WIND         %.1f km/h
                        PRECIP       %d%% chance
                        TOMORROW     high %.1f C  /  low %.1f C""",
                label, condition, temp, feels, hi, lo, humidity, wind, pop,
                daily.path("temperature_2m_max").path(1).asDouble(),
                daily.path("temperature_2m_min").path(1).asDouble());

        return SkillResult.of(spoken, detail);
    }

    /** Pull a place name out of "weather in Tokyo" / "Tokyo weather". */
    private static String extractPlace(String input) {
        String configured = Config.get().str("weather.location");
        var m = java.util.regex.Pattern
                .compile("(?:in|at|for)\\s+([A-Za-z\\s,'-]+?)(?:\\s+(?:today|tomorrow|now|right now))?\\s*[?.!]*$")
                .matcher(input);
        if (m.find()) {
            String place = m.group(1).trim();
            if (!place.isEmpty() && !place.equalsIgnoreCase("here")) return place;
        }
        return configured.isBlank() ? null : configured;
    }

    /** Coarse location by IP, cached for the session. */
    private double[] hereCoords() throws Exception {
        if (cachedCoords != null) return cachedCoords;
        try {
            JsonNode ip = Http.getJson("https://ipapi.co/json/");
            cachedCity = ip.path("city").asText("") + (ip.path("country_name").asText("").isBlank()
                    ? "" : ", " + ip.path("country_name").asText());
            cachedCoords = new double[]{ip.path("latitude").asDouble(), ip.path("longitude").asDouble()};
            if (cachedCoords[0] == 0 && cachedCoords[1] == 0) throw new IllegalStateException("no fix");
            return cachedCoords;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "I do not know where you are. Set weather.location in " + Config.get().file());
        }
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
