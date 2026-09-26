package com.bilicharge.archive;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AliyunMediaStore implements MediaStore {
    private final OSS client;
    private final String bucket;

    AliyunMediaStore(@Value("${app.media.region:}") String region,
                     @Value("${app.media.bucket:}") String bucket,
                     @Value("${app.media.access-key-id:}") String accessKeyId,
                     @Value("${app.media.access-key-secret:}") String accessKeySecret) {
        boolean any = !region.isBlank() || !bucket.isBlank() || !accessKeyId.isBlank() || !accessKeySecret.isBlank();
        boolean all = !region.isBlank() && !bucket.isBlank() && !accessKeyId.isBlank() && !accessKeySecret.isBlank();
        if (any && !all) throw new IllegalArgumentException("OSS configuration is incomplete");
        if (all && (!region.matches("[a-z0-9-]+") || !bucket.matches("[a-z0-9-]+")))
            throw new IllegalArgumentException("OSS region or bucket is invalid");
        this.bucket = bucket;
        if (all) {
            ClientBuilderConfiguration config = new ClientBuilderConfiguration();
            config.setSignatureVersion(SignVersion.V4);
            config.setConnectionTimeout(5000);
            config.setSocketTimeout(15000);
            client = OSSClientBuilder.create()
                    .endpoint("https://oss-" + region + ".aliyuncs.com")
                    .region(region)
                    .credentialsProvider(new DefaultCredentialProvider(accessKeyId, accessKeySecret))
                    .clientConfiguration(config).build();
        } else {
            client = null;
        }
    }

    @Override public boolean configured() { return client != null; }

    @Override
    public void putPrivate(String key, byte[] bytes, String contentType) {
        requirePrivateBucket();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength(bytes.length);
        client.putObject(bucket, key, new ByteArrayInputStream(bytes), metadata);
        // READY is committed only after the object ACL has also been made private.
        client.setObjectAcl(bucket, key, CannedAccessControlList.Private);
    }

    @Override
    public URI signedGet(String key, Duration lifetime) {
        requirePrivateBucket();
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + lifetime.toMillis()));
        return URI.create(client.generatePresignedUrl(request).toString());
    }

    private void requirePrivateBucket() {
        if (client == null) throw new IllegalStateException("OSS is not configured");
        if (client.getBucketAcl(bucket).getCannedACL() != CannedAccessControlList.Private)
            throw new IllegalStateException("OSS bucket must be private");
    }

    @PreDestroy
    void close() {
        if (client != null) client.shutdown();
    }
}
