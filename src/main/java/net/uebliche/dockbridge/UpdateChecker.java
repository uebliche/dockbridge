package net.uebliche.dockbridge;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles Modrinth update lookups. Fetches the latest versions once and
 * compares them against the locally running plugin version.
 */
public final class UpdateChecker {

    private static final String API_URL = "https://api.modrinth.com/v2/project/dockbridge/version";
    private static final String USER_AGENT = "DockBridge-UpdateChecker";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})([a-z]*)$");

    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson;

    public UpdateChecker(Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    /**
     * Fetches the latest available version and compares it with {@code currentVersion}.
     *
     * @return optional containing a newer version if available.
     */
    public Optional<String> checkForUpdate(String currentVersion) {
        Objects.requireNonNull(currentVersion, "currentVersion");

        ModrinthVersion[] versions = fetchVersions();
        if (versions == null || versions.length == 0) {
            return Optional.empty();
        }

        String latest = null;
        for (ModrinthVersion version : versions) {
            if (version == null || version.versionNumber == null || version.versionNumber.isBlank()) {
                continue;
            }
            String normalized = version.versionNumber.trim();
            if (latest == null || compareVersions(normalized, latest) > 0) {
                latest = normalized;
            }
        }

        if (latest != null && compareVersions(latest, currentVersion) > 0) {
            return Optional.of(latest);
        }
        return Optional.empty();
    }

    private ModrinthVersion[] fetchVersions() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Modrinth API returned status {} while checking for updates.", response.statusCode());
                return null;
            }
            return gson.fromJson(response.body(), ModrinthVersion[].class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Modrinth update check was interrupted.");
        } catch (IOException e) {
            logger.warn("Modrinth update check failed: {}", e.getMessage());
        } catch (JsonSyntaxException e) {
            logger.warn("Could not parse Modrinth response: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Compares two versions following the schema YYYY-MM-DD[a-z]* where an empty suffix
     * is older than any alphabetic suffix.
     */
    public static int compareVersions(String left, String right) {
        VersionParts leftParts = parseVersion(left);
        VersionParts rightParts = parseVersion(right);

        int dateCompare = leftParts.date.compareTo(rightParts.date);
        if (dateCompare != 0) {
            return dateCompare;
        }

        if (leftParts.suffix.isEmpty() && rightParts.suffix.isEmpty()) {
            return 0;
        }
        if (leftParts.suffix.isEmpty()) {
            return -1;
        }
        if (rightParts.suffix.isEmpty()) {
            return 1;
        }

        return leftParts.suffix.compareTo(rightParts.suffix);
    }

    private static VersionParts parseVersion(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (!matcher.matches()) {
            return new VersionParts(version, "");
        }
        String date = matcher.group(1);
        String suffix = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase();
        return new VersionParts(date, suffix);
    }

    private static final class ModrinthVersion {
        // JSON field name uses snake_case.
        @SerializedName("version_number")
        private String versionNumber;
    }

    private record VersionParts(String date, String suffix) {
    }
}
