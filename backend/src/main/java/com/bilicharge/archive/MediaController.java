package com.bilicharge.archive;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/media")
final class MediaController {
    private static final Duration SIGNED_LIFETIME = Duration.ofMinutes(10);
    private final MediaMapper mapper;
    private final MediaStore store;

    MediaController(MediaMapper mapper, MediaStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    @GetMapping("/dynamics/{dynamicId}/{position}")
    ResponseEntity<Void> dynamic(@PathVariable String dynamicId, @PathVariable int position) {
        validate(dynamicId, position);
        return redirect(mapper.dynamicImage(dynamicId, position));
    }

    @GetMapping("/comments/{dynamicId}/{rpid}/{position}")
    ResponseEntity<Void> comment(@PathVariable String dynamicId, @PathVariable String rpid,
                                 @PathVariable int position) {
        validate(dynamicId, position);
        validateId(rpid);
        return redirect(mapper.commentImage(dynamicId, rpid, position));
    }

    private ResponseEntity<Void> redirect(MediaRows.Image image) {
        if (image == null) throw new AdminException(HttpStatus.NOT_FOUND, "NOT_FOUND", "图片不存在");
        if ("UNAVAILABLE".equals(image.uploadStatus))
            throw new AdminException(HttpStatus.GONE, "MEDIA_UNAVAILABLE", "图片来源不可用");
        if (!"READY".equals(image.uploadStatus) || image.ossKey == null)
            throw new AdminException(HttpStatus.CONFLICT, "MEDIA_PENDING", "图片尚未就绪");
        if (!store.configured())
            throw new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORE_UNAVAILABLE", "图片存储暂不可用");
        try {
            URI signed = store.signedGet(image.ossKey, SIGNED_LIFETIME);
            if (!"https".equalsIgnoreCase(signed.getScheme()))
                throw new IllegalStateException("OSS signed URL must use HTTPS");
            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(signed);
            headers.setCacheControl(CacheControl.noStore());
            headers.set("Referrer-Policy", "no-referrer");
            return new ResponseEntity<>(headers, HttpStatus.FOUND);
        } catch (RuntimeException error) {
            throw new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORE_UNAVAILABLE", "图片存储暂不可用");
        }
    }

    private void validate(String dynamicId, int position) {
        validateId(dynamicId);
        if (position < 0 || position > 65535)
            throw new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POSITION", "图片位置无效");
    }

    private void validateId(String id) {
        if (!id.matches("[1-9][0-9]{0,31}"))
            throw new AdminException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ID", "ID 必须是数字字符串");
    }
}
