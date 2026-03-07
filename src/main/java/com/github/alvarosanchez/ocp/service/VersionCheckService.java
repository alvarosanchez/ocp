package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.command.OcpVersionProvider;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
public final class VersionCheckService {

    static final Duration VERSION_CHECK_INTERVAL = Duration.ofHours(24);
    static final URI DEFAULT_RELEASES_URI = URI.create("https://api.github.com/repos/alvarosanchez/ocp/releases/latest");

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    private final ObjectMapper objectMapper;
    private final RepositoryService repositoryService;
    private final Clock clock;
    private final HttpClient httpClient;
    private final URI releasesUri;

    @Inject
    VersionCheckService(ObjectMapper objectMapper, RepositoryService repositoryService) {
        this(
            objectMapper,
            repositoryService,
            Clock.systemUTC(),
            HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build(),
            DEFAULT_RELEASES_URI
        );
    }

    VersionCheckService(ObjectMapper objectMapper, RepositoryService repositoryService, Clock clock, HttpClient httpClient, URI releasesUri) {
        this.objectMapper = objectMapper;
        this.repositoryService = repositoryService;
        this.clock = clock;
        this.httpClient = httpClient;
        this.releasesUri = releasesUri;
    }

    public VersionCheckResult check() {
        String currentVersion = OcpVersionProvider.readVersion();
        OcpConfigFile configFile = repositoryService.loadConfigFile();
        OcpConfigOptions config = configFile.config();
        Instant now = clock.instant();
        Instant lastCheckedAt = instantOf(config.lastOcpVersionCheckEpochSeconds());
        if (shouldUseCachedResult(lastCheckedAt, now)) {
            return VersionCheckResult.from(currentVersion, config.latestOcpVersion());
        }

        String latestVersion = config.latestOcpVersion();
        try {
            latestVersion = fetchLatestVersion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            latestVersion = config.latestOcpVersion();
        }

        repositoryService.saveConfig(new OcpConfigFile(updatedOptions(config, now, latestVersion), configFile.repositories()));
        return VersionCheckResult.from(currentVersion, latestVersion);
    }

    private String fetchLatestVersion() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(releasesUri)
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ocp-version-check")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Version endpoint returned HTTP " + response.statusCode());
        }

        Map<?, ?> payload = objectMapper.readValue(response.body(), Map.class);
        Object tagNameValue = payload.get("tag_name");
        if (!(tagNameValue instanceof String tagName) || tagName.isBlank()) {
            throw new IllegalStateException("Version endpoint response did not include tag_name");
        }
        return normalizeVersion(tagName);
    }

    private OcpConfigOptions updatedOptions(OcpConfigOptions currentOptions, Instant now, String latestVersion) {
        return new OcpConfigOptions(
            currentOptions.activeProfile(),
            now.getEpochSecond(),
            latestVersion
        );
    }

    private static Instant instantOf(Long epochSeconds) {
        if (epochSeconds == null) {
            return null;
        }
        return Instant.ofEpochSecond(epochSeconds);
    }

    private static boolean shouldUseCachedResult(Instant lastCheckedAt, Instant now) {
        if (lastCheckedAt == null || lastCheckedAt.isAfter(now)) {
            return false;
        }
        return Duration.between(lastCheckedAt, now).compareTo(VERSION_CHECK_INTERVAL) < 0;
    }

    static String normalizeVersion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    static int compareVersions(String left, String right) {
        ParsedVersion leftVersion = ParsedVersion.parse(left);
        ParsedVersion rightVersion = ParsedVersion.parse(right);
        return leftVersion.compareTo(rightVersion);
    }

    public record VersionCheckResult(String currentVersion, String latestVersion, boolean updateAvailable) {

        static VersionCheckResult from(String currentVersion, String latestVersion) {
            String normalizedCurrentVersion = normalizeVersion(currentVersion);
            String normalizedLatestVersion = normalizeVersion(latestVersion);
            boolean updateAvailable = false;
            if (!normalizedLatestVersion.isBlank()) {
                try {
                    updateAvailable = compareVersions(normalizedLatestVersion, normalizedCurrentVersion) > 0;
                } catch (RuntimeException e) {
                    updateAvailable = false;
                }
            }
            return new VersionCheckResult(normalizedCurrentVersion, normalizedLatestVersion, updateAvailable);
        }

        public String noticeMessage() {
            if (!updateAvailable) {
                return null;
            }
            return "New ocp version available: "
                + latestVersion
                + " (current "
                + currentVersion
                + ")\n"
                + "Run brew upgrade ocp to get the new version";
        }
    }

    private record ParsedVersion(List<Integer> numericParts, String qualifier) implements Comparable<ParsedVersion> {

        static ParsedVersion parse(String version) {
            String normalized = normalizeVersion(version);
            if (normalized.isBlank()) {
                return new ParsedVersion(List.of(0), "");
            }

            int buildMetadataIndex = normalized.indexOf('+');
            if (buildMetadataIndex >= 0) {
                normalized = normalized.substring(0, buildMetadataIndex);
            }

            String[] mainAndQualifier = normalized.split("-", 2);
            String[] tokens = mainAndQualifier[0].split("\\.");
            ArrayList<Integer> parts = new ArrayList<>();
            for (String token : tokens) {
                if (token.isBlank()) {
                    parts.add(0);
                    continue;
                }
                parts.add(Integer.parseInt(token));
            }
            String qualifier = mainAndQualifier.length > 1 ? mainAndQualifier[1] : "";
            return new ParsedVersion(List.copyOf(parts), qualifier);
        }

        @Override
        public int compareTo(ParsedVersion other) {
            int maxSize = Math.max(numericParts.size(), other.numericParts.size());
            for (int index = 0; index < maxSize; index++) {
                int leftPart = index < numericParts.size() ? numericParts.get(index) : 0;
                int rightPart = index < other.numericParts.size() ? other.numericParts.get(index) : 0;
                if (leftPart != rightPart) {
                    return Integer.compare(leftPart, rightPart);
                }
            }
            if (qualifier.isBlank() && other.qualifier.isBlank()) {
                return 0;
            }
            if (qualifier.isBlank()) {
                return 1;
            }
            if (other.qualifier.isBlank()) {
                return -1;
            }
            return qualifier.compareTo(other.qualifier);
        }
    }
}
