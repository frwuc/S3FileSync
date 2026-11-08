package com.frwuc.s3fileuploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
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
    public void remoteKeyKeepsPrefixSubDirAndOriginalName() {
        String key = ScanRules.buildRemoteKey("daka_files", "2024/10/", "报告 1.pdf");
        assertTrue(key.startsWith("daka_files/2024/10/"));
        assertTrue(key.endsWith("_报告 1.pdf"));

        String rootKey = ScanRules.buildRemoteKey("", "", "a.zip");
        assertTrue(rootKey.endsWith("_a.zip"));
        assertFalse(rootKey.startsWith("/"));
    }
}
