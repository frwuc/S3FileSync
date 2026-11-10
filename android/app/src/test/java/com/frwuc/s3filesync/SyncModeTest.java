package com.frwuc.s3filesync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 同步方向：默认值、两端开关、持久化字符串解析。 */
public class SyncModeTest {

    @Test
    public void defaultIsUploadOnlyForBackwardCompatibility() {
        assertEquals(SyncMode.UPLOAD, SyncMode.DEFAULT);
        assertEquals(SyncMode.DEFAULT, SyncMode.fromName(null));
        assertEquals(SyncMode.DEFAULT, SyncMode.fromName("not-a-mode"));
    }

    @Test
    public void directionFlags() {
        assertTrue(SyncMode.UPLOAD.doUpload());
        assertFalse(SyncMode.UPLOAD.doDownload());

        assertFalse(SyncMode.DOWNLOAD.doUpload());
        assertTrue(SyncMode.DOWNLOAD.doDownload());

        assertTrue(SyncMode.BOTH.doUpload());
        assertTrue(SyncMode.BOTH.doDownload());
    }

    @Test
    public void fromNameRestoresEveryMode() {
        for (SyncMode mode : SyncMode.values()) {
            assertEquals(mode, SyncMode.fromName(mode.name()));
        }
    }

    @Test
    public void labelsMatchValues() {
        String[] labels = SyncMode.labels();
        assertEquals(SyncMode.values().length, labels.length);
        for (int i = 0; i < labels.length; i++) {
            assertEquals(SyncMode.values()[i].label, labels[i]);
        }
    }
}
