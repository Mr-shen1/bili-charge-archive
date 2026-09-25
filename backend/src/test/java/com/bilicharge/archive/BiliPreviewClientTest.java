package com.bilicharge.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class BiliPreviewClientTest {
    private static final String UID = "550494308";
    private static final String DYNAMIC_ID = "1251460007328743432";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void validChargeWordHasCommentTarget() throws Exception {
        BiliPreviewClient.Dynamic parsed = BiliPreviewClient.parseDynamic(item(UID, "充电专属",
                "DYNAMIC_TYPE_WORD", "MAJOR_TYPE_OPUS", "12345", 17), UID, DYNAMIC_ID);
        assertThat(parsed.dynamicId()).isEqualTo(DYNAMIC_ID);
        assertThat(parsed.commentOid()).isEqualTo("12345");
        assertThat(parsed.commentType()).isEqualTo(17);
    }

    @Test
    void rejectsOtherAuthorNonChargeAndNonTargetType() throws Exception {
        assertInvalid(item("42", "充电专属", "DYNAMIC_TYPE_WORD", "MAJOR_TYPE_OPUS", "12345", 17));
        assertInvalid(item(UID, "", "DYNAMIC_TYPE_WORD", "MAJOR_TYPE_OPUS", "12345", 17));
        assertInvalid(item(UID, "充电专属", "DYNAMIC_TYPE_AV", "MAJOR_TYPE_ARCHIVE", "12345", 1));
        assertInvalid(item(UID, "充电专属", "DYNAMIC_TYPE_WORD", "MAJOR_TYPE_OPUS", "", 17));
    }

    private void assertInvalid(JsonNode item) {
        assertThatThrownBy(() -> BiliPreviewClient.parseDynamic(item, UID, DYNAMIC_ID))
                .isInstanceOf(AdminException.class);
    }

    private JsonNode item(String uid, String badge, String type, String major, String oid, int commentType)
            throws Exception {
        return json.readTree("""
                {"id_str":"%s","type":"%s","basic":{"comment_id_str":"%s","comment_type":%d},
                 "modules":{"module_author":{"mid":"%s","name":"测试 UP","icon_badge":{"text":"%s"}},
                 "module_dynamic":{"major":{"type":"%s","opus":{"title":"测试动态"}}}}}
                """.formatted(DYNAMIC_ID, type, oid, commentType, uid, badge, major));
    }
}
