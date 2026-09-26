package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class LiveBucketTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9P4VAAAAAASUVORK5CYII=");

    @Test
    @EnabledIfEnvironmentVariable(named = "OSS_BUCKET", matches = ".+")
    void privateObjectAndExpiringSignature() throws Exception {
        String region = System.getenv("OSS_REGION");
        String bucket = System.getenv("OSS_BUCKET");
        String accessId = System.getenv("OSS_ACCESS_KEY_ID");
        String accessSecret = System.getenv("OSS_ACCESS_KEY_SECRET");
        assertThat(region).matches("[a-z0-9-]+");
        assertThat(bucket).matches("[a-z0-9-]+");
        assertThat(accessId).isNotBlank();
        assertThat(accessSecret).isNotBlank();

        String key = "bili-charge/m6-probe/" + UUID.randomUUID() + ".png";
        AliyunMediaStore store = new AliyunMediaStore(region, bucket, accessId, accessSecret);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            store.putPrivate(key, PNG, "image/png");
            URI first = store.signedGet(key, Duration.ofSeconds(5));
            URI unsigned = new URI(first.getScheme(), first.getAuthority(), first.getPath(), null, null);
            assertThat(status(http, unsigned)).isEqualTo(403);
            assertThat(bytes(http, first)).isEqualTo(PNG);
            Thread.sleep(6500);
            assertThat(status(http, first)).isEqualTo(403);
            URI renewed = store.signedGet(key, Duration.ofSeconds(10));
            assertThat(renewed).isNotEqualTo(first);
            assertThat(bytes(http, renewed)).isEqualTo(PNG);
        } finally {
            store.close();
            // Cleanup uses a separate optional permission; missing it does not weaken the privacy test.
            ClientBuilderConfiguration config = new ClientBuilderConfiguration();
            config.setSignatureVersion(SignVersion.V4);
            OSS cleanup = OSSClientBuilder.create()
                    .endpoint("https://oss-" + region + ".aliyuncs.com")
                    .region(region)
                    .credentialsProvider(new DefaultCredentialProvider(accessId, accessSecret))
                    .clientConfiguration(config).build();
            try {
                cleanup.deleteObject(bucket, key);
            } catch (RuntimeException error) {
                System.out.println("OSS probe cleanup unavailable; object remains under bili-charge/m6-probe/");
            } finally {
                cleanup.shutdown();
            }
        }
    }

    private int status(HttpClient http, URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private byte[] bytes(HttpClient http, URI uri) throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
