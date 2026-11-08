package com.frwuc.s3fileuploader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 浏览列表排序规则：目录恒在前，文件按名称 / 时间升或降。 */
public class BrowseSortTest {

    private static BrowseEntry dir(String name) {
        return new BrowseEntry(name, "/x/" + name, true, 0L, 0L);
    }

    private static BrowseEntry file(String name, long mtimeMs) {
        return new BrowseEntry(name, "/x/" + name, false, 100L, mtimeMs);
    }

    private static List<String> names(List<BrowseEntry> entries) {
        List<String> out = new ArrayList<>();
        for (BrowseEntry e : entries) out.add(e.name);
        return out;
    }

    private static List<BrowseEntry> sample() {
        List<BrowseEntry> list = new ArrayList<>();
        list.add(file("b.png", 2000L));
        list.add(dir("zdir"));
        list.add(file("c.png", 3000L));
        list.add(dir("adir"));
        list.add(file("a.png", 1000L));
        return list;
    }

    @Test
    public void directoriesAlwaysComeFirst() {
        for (BrowseSort sort : BrowseSort.values()) {
            List<BrowseEntry> list = sample();
            sort.sort(list);
            assertEquals("排序方式 " + sort, true, list.get(0).dir);
            assertEquals("排序方式 " + sort, true, list.get(1).dir);
            assertEquals("adir", list.get(0).name);
            assertEquals("zdir", list.get(1).name);
        }
    }

    @Test
    public void nameAscendingThenDescending() {
        List<BrowseEntry> asc = sample();
        BrowseSort.NAME_ASC.sort(asc);
        assertEquals(java.util.Arrays.asList("adir", "zdir", "a.png", "b.png", "c.png"), names(asc));

        List<BrowseEntry> desc = sample();
        BrowseSort.NAME_DESC.sort(desc);
        assertEquals(java.util.Arrays.asList("adir", "zdir", "c.png", "b.png", "a.png"), names(desc));
    }

    @Test
    public void timeSortOnlyAffectsFiles() {
        List<BrowseEntry> newestFirst = sample();
        BrowseSort.TIME_DESC.sort(newestFirst);
        assertEquals(java.util.Arrays.asList("adir", "zdir", "c.png", "b.png", "a.png"),
                names(newestFirst));

        List<BrowseEntry> oldestFirst = sample();
        BrowseSort.TIME_ASC.sort(oldestFirst);
        assertEquals(java.util.Arrays.asList("adir", "zdir", "a.png", "b.png", "c.png"),
                names(oldestFirst));
    }

    @Test
    public void toggleSwitchesDirectionWithinSameMode() {
        assertEquals(BrowseSort.NAME_DESC, BrowseSort.NAME_ASC.toggle());
        assertEquals(BrowseSort.NAME_ASC, BrowseSort.NAME_DESC.toggle());
        assertEquals(BrowseSort.TIME_DESC, BrowseSort.TIME_ASC.toggle());
        assertEquals(BrowseSort.TIME_ASC, BrowseSort.TIME_DESC.toggle());
        assertEquals(true, BrowseSort.TIME_ASC.byTime());
        assertEquals(false, BrowseSort.NAME_ASC.byTime());
    }
}
