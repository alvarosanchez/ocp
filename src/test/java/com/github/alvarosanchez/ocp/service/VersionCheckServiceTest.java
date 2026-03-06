package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionCheckServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private String previousConfigDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        previousConfigDir = System.getProperty("ocp.config.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
        if (previousConfigDir == null) {
            System.clearProperty("ocp.config.dir");
        } else {
            System.setProperty("ocp.config.dir", previousConfigDir);
        }
    }

    @Test
    void checkCachesSuccessfulResultAndReusesItWithin24Hours() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("{\"tag_name\":\"v999.0.0\"}");
        URI uri = URI.create("https://example.test/releases/latest");
        HttpClient httpClient = new StubHttpClient(requests, body);
        Instant now = Instant.parse("2026-03-06T12:00:00Z");

        VersionCheckService firstService = new VersionCheckService(objectMapper, Clock.fixed(now, ZoneOffset.UTC), httpClient, uri);
        VersionCheckService.VersionCheckResult firstResult = firstService.check();

        assertTrue(firstResult.updateAvailable());
        assertEquals("999.0.0", firstResult.latestVersion());
        assertEquals(1, requests.get());

        OcpConfigFile storedConfig = readStoredConfig();
        assertEquals(now.getEpochSecond(), storedConfig.config().lastOcpVersionCheckEpochSeconds());
        assertEquals("999.0.0", storedConfig.config().latestOcpVersion());

        VersionCheckService secondService = new VersionCheckService(
            objectMapper,
            Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC),
            httpClient,
            uri
        );
        VersionCheckService.VersionCheckResult secondResult = secondService.check();

        assertTrue(secondResult.updateAvailable());
        assertEquals("999.0.0", secondResult.latestVersion());
        assertEquals(1, requests.get());
    }

    @Test
    void micronautCanInstantiateVersionCheckServiceBean() {
        assertNotNull(applicationContext.getBean(VersionCheckService.class));
    }

    @Test
    void checkRefreshesAfter24HoursHaveElapsed() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("{\"tag_name\":\"v999.0.0\"}");
        URI uri = URI.create("https://example.test/releases/latest");
        HttpClient httpClient = new StubHttpClient(requests, body);
        Instant now = Instant.parse("2026-03-06T12:00:00Z");

        new VersionCheckService(objectMapper, Clock.fixed(now, ZoneOffset.UTC), httpClient, uri).check();
        body.set("{\"tag_name\":\"v999.1.0\"}");

        VersionCheckService.VersionCheckResult refreshedResult = new VersionCheckService(
            objectMapper,
            Clock.fixed(now.plus(VersionCheckService.VERSION_CHECK_INTERVAL).plusSeconds(1), ZoneOffset.UTC),
            httpClient,
            uri
        ).check();

        assertTrue(refreshedResult.updateAvailable());
        assertEquals("999.1.0", refreshedResult.latestVersion());
        assertEquals(2, requests.get());
    }

    @Test
    void checkRecordsFailedAttemptAndSkipsRetryUntilCacheExpires() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("{}");
        URI uri = URI.create("https://example.test/releases/latest");
        HttpClient httpClient = new StubHttpClient(requests, body);
        Instant now = Instant.parse("2026-03-06T12:00:00Z");

        VersionCheckService firstService = new VersionCheckService(objectMapper, Clock.fixed(now, ZoneOffset.UTC), httpClient, uri);
        VersionCheckService.VersionCheckResult firstResult = firstService.check();

        assertFalse(firstResult.updateAvailable());
        assertEquals(1, requests.get());
        assertEquals(now.getEpochSecond(), readStoredConfig().config().lastOcpVersionCheckEpochSeconds());

        VersionCheckService secondService = new VersionCheckService(
            objectMapper,
            Clock.fixed(now.plusSeconds(1800), ZoneOffset.UTC),
            httpClient,
            uri
        );
        VersionCheckService.VersionCheckResult secondResult = secondService.check();

        assertFalse(secondResult.updateAvailable());
        assertEquals(1, requests.get());
    }

    @Test
    void checkPreservesConfiguredRepositoriesWhenSavingVersionMetadata() throws IOException {
        RepositoryEntry repository = new RepositoryEntry("sample", "git@example.com:sample.git", "/tmp/sample");
        writeConfig(new OcpConfigFile(new OcpConfigOptions("active"), java.util.List.of(repository)));

        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("{\"tag_name\":\"v999.0.0\"}");
        VersionCheckService service = new VersionCheckService(
            objectMapper,
            Clock.fixed(Instant.parse("2026-03-06T12:00:00Z"), ZoneOffset.UTC),
            new StubHttpClient(requests, body),
            URI.create("https://example.test/releases/latest")
        );

        service.check();

        OcpConfigFile storedConfig = readStoredConfig();
        assertEquals(1, storedConfig.repositories().size());
        assertEquals(repository, storedConfig.repositories().getFirst());
        assertEquals("active", storedConfig.config().activeProfile());
    }

    @Test
    void checkIgnoresMalformedVersionStringsWithoutFailing() throws IOException {
        writeConfig(new OcpConfigFile(new OcpConfigOptions(null, null, "release-2026"), java.util.List.of()));

        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("{\"tag_name\":\"release-next\"}");
        VersionCheckService service = new VersionCheckService(
            objectMapper,
            Clock.fixed(Instant.parse("2026-03-06T12:00:00Z"), ZoneOffset.UTC),
            new StubHttpClient(requests, body),
            URI.create("https://example.test/releases/latest")
        );

        VersionCheckService.VersionCheckResult result = service.check();

        assertFalse(result.updateAvailable());
        assertEquals("release-next", result.latestVersion());
        assertNull(result.noticeMessage());
    }

    @Test
    void checkDoesNotTreatFutureTimestampAsFreshCache() throws IOException {
        Instant now = Instant.parse("2026-03-06T12:00:00Z");
        writeConfig(new OcpConfigFile(new OcpConfigOptions(null, now.plusSeconds(7200).getEpochSecond(), "999.0.0"), java.util.List.of()));

        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("{\"tag_name\":\"v999.1.0\"}");
        VersionCheckService service = new VersionCheckService(
            objectMapper,
            Clock.fixed(now, ZoneOffset.UTC),
            new StubHttpClient(requests, body),
            URI.create("https://example.test/releases/latest")
        );

        VersionCheckService.VersionCheckResult result = service.check();

        assertTrue(result.updateAvailable());
        assertEquals("999.1.0", result.latestVersion());
        assertEquals(1, requests.get());
        assertEquals(now.getEpochSecond(), readStoredConfig().config().lastOcpVersionCheckEpochSeconds());
    }

    @Test
    void noticeMessageUsesUpgradeGuidanceTemplate() {
        VersionCheckService.VersionCheckResult result = VersionCheckService.VersionCheckResult.from("0.1.0", "0.6.2");

        assertEquals(
            "New ocp version available: 0.6.2 (current 0.1.0)\nRun brew upgrade ocp to get the new version",
            result.noticeMessage()
        );
    }

    private OcpConfigFile readStoredConfig() throws IOException {
        return objectMapper.readValue(Files.readString(configFile()), OcpConfigFile.class);
    }

    private void writeConfig(OcpConfigFile configFile) throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), objectMapper.writeValueAsString(configFile));
    }

    private Path configFile() {
        return Path.of(System.getProperty("ocp.config.dir")).resolve("config.json");
    }

    private static final class StubHttpClient extends HttpClient {

        private final AtomicInteger requests;
        private final AtomicReference<String> body;

        private StubHttpClient(AtomicInteger requests, AtomicReference<String> body) {
            this.requests = requests;
            this.body = body;
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.incrementAndGet();
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new StubHttpResponse(request, body.get());
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }
    }

    private static final class StubHttpResponse implements HttpResponse<String> {

        private final HttpRequest request;
        private final String body;

        private StubHttpResponse(HttpRequest request, String body) {
            this.request = request;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
