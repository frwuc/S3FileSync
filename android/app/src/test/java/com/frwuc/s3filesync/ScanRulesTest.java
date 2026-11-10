package com.frwuc.s3filesync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步扫描规则：递归子目录、跳过隐藏项、云端 key 保留相对路径。
 */
public class ScanRulesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void collectFilesWalksSubDirectoriesAndSkipsHidden() throws Exception {
        File root = tmp.newFolder("root");
        File sub = new File(root, "2024/10");
        assertTrue(sub.mkdirs());
        assertTrue(new File(root, "a.jpg").createNewFile());
        assertTrue(new File(sub, "b.pdf").createNewFile());
        assertTrue(new File(root, ".nomedia").createNewFile());
        File hiddenDir = new File(root, ".thumbnails");
        assertTrue(hiddenDir.mkdirs());
        assertTrue(new File(hiddenDir, "c.png").createNewFile());

        List<File> out = new ArrayList<>();
        ScanRules.collectFiles(root, out, 0);

        // 隐藏文件与隐藏目录里的内容都不应被收集
        assertEquals(2, out.size());
        boolean hasA = false, hasB = false;
        for (File f : out) {
            if ("a.jpg".equals(f.getName())) hasA = true;
            if ("b.pdf".equals(f.getName())) hasB = true;
        }
        assertTrue(hasA && hasB);
    }

    @Test
    public void relativeDirKeepsSubPath() throws Exception {
        File root = tmp.newFolder("r2");
        File deep = new File(root, "x/y");
        assertTrue(deep.mkdirs());
        File f = new File(deep, "z.txt");
        assertTrue(f.createNewFile());
        assertEquals("x/y/", ScanRules.relativeDirOf(root, f));

        File top = new File(root, "top.txt");
        assertTrue(top.createNewFile());
        assertEquals("", ScanRules.relativeDirOf(root, top));
    }

    @Test
    public void canonicalBoundaryRejectsSiblingAndParentPaths() throws Exception {
        File root = tmp.newFolder("root-boundary").getCanonicalFile();
        assertTrue(ScanRules.isWithinRoot(root, new File(root, "sub/file.txt").getCanonicalFile()));
        assertTrue(ScanRules.isWithinRoot(root, root));
        assertFalse(ScanRules.isWithinRoot(root,
                new File(root, "../root-boundary-else/secret.txt").getCanonicalFile()));
        assertFalse(ScanRules.isWithinRoot(root,
                new File(root, "../outside.txt").getCanonicalFile()));
    }

    @Test
    public void collectFilesDoesNotFollowSymlinkOutsideRoot() throws Exception {
        File root = tmp.newFolder("root-links");
        File outside = tmp.newFolder("outside-links");
        File secret = new File(outside, "secret.txt");
        assertTrue(secret.createNewFile());
        assertTrue(new File(root, "normal.txt").createNewFile());
        try {
            java.nio.file.Files.createSymbolicLink(
                    new File(root, "linked-secret.txt").toPath(), secret.toPath());
            java.nio.file.Files.createSymbolicLink(
                    new File(root, "linked-dir").toPath(), outside.toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // Windows 无创建符号链接权限时无法执行该用例；边界判断由上一用例覆盖，Linux CI 会执行。
            return;
        }

        List<File> out = new ArrayList<>();
        ScanRules.collectFiles(root, out, 0);
        assertEquals(1, out.size());
        assertEquals("normal.txt", out.get(0).getName());
    }

    @Test
    public void remoteKeyKeepsPrefixSubDirAndOriginalName() {
        // 文件名必须与本地完全一致：不加时间戳、不加任何前缀
        String key = ScanRules.buildRemoteKey("daka_files", "2024/10/", "报告 1.pdf");
        assertEquals("daka_files/2024/10/报告 1.pdf", key);

        String rootKey = ScanRules.buildRemoteKey("", "", "a.zip");
        assertEquals("a.zip", rootKey);
        assertFalse(rootKey.startsWith("/"));

        // 云端目录 / 子目录首尾斜杠随意，结果一致且不会出现时间戳前缀
        assertEquals("daka_files/x/y/b.pdf",
                ScanRules.buildRemoteKey("/daka_files/", "x/y", "b.pdf"));
        assertFalse(key.matches(".*[0-9]{10,}_.*"));
    }

    @Test
    public void downloadPathKeepsCloudRelativeDirAndOriginalName() throws Exception {
        File root = tmp.newFolder("local-root");
        File target = ScanRules.localTargetFile(root.getAbsolutePath(), "daka_files",
                "daka_files/2024/10/报告 1.pdf");
        assertNotNull(target);
        // 落盘文件名与云端完全一致，只还原相对子目录
        assertEquals("报告 1.pdf", target.getName());
        assertEquals(new File(root, "2024/10/报告 1.pdf").getAbsolutePath(),
                target.getAbsolutePath());

        File flat = ScanRules.localTargetFile(root.getAbsolutePath(), "", "a.zip");
        assertNotNull(flat);
        assertEquals("a.zip", flat.getName());
        assertEquals(new File(root, "a.zip").getAbsolutePath(), flat.getAbsolutePath());
    }

    @Test
    public void downloadPathRejectsUnsafeKeys() {
        String root = new File(System.getProperty("java.io.tmpdir"), "s3fu-root")
                .getAbsolutePath();
        assertNull(ScanRules.localTargetFile(root, "daka_files", "../outside.txt"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "daka_files/../x.txt"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "daka_files/a/../../x.txt"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "daka_files/.hidden"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "daka_files/"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "daka_files//x.txt"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "other/x.txt"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "/etc/passwd"));
        assertNull(ScanRules.localTargetFile(root, "daka_files", "daka_files/C:\\x.txt"));
        assertNull(ScanRules.localTargetFile("", "daka_files", "daka_files/x.txt"));
    }

    @Test
    public void relativePathOfKeyStripsCloudDir() {
        assertEquals("2024/10/a.jpg",
                ScanRules.relativePathOfKey("daka_files", "daka_files/2024/10/a.jpg"));
        assertEquals("a.jpg", ScanRules.relativePathOfKey("", "a.jpg"));
        assertNull(ScanRules.relativePathOfKey("daka_files", "other/a.jpg"));
        assertNull(ScanRules.relativePathOfKey("daka_files", ""));
    }

    @Test
    public void normalizeCloudDirTrimsSlashes() {
        assertEquals("", ScanRules.normalizeCloudDir(null));
        assertEquals("", ScanRules.normalizeCloudDir(""));
        assertEquals("", ScanRules.normalizeCloudDir("///"));
        assertEquals("daka_files/", ScanRules.normalizeCloudDir("/daka_files/"));
        assertEquals("a/b/", ScanRules.normalizeCloudDir("a/b"));
    }
}
