package com.frwuc.s3filesync;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 浏览列表的排序方式：名称 / 时间，各支持升降序。
 *
 * <p>统一规则：**目录始终排在文件前面**（目录之间固定按名称升序，时间对目录没有意义），
 * 文件按选中规则排序。这样不管怎么切排序，都不会出现文件夹被文件夹在中间的情况。
 */
public enum BrowseSort {

    NAME_ASC("名称 ↑"),
    NAME_DESC("名称 ↓"),
    TIME_ASC("时间 ↑"),
    TIME_DESC("时间 ↓");

    /**
     * 默认按时间「新到旧」（时间 ↓），与手机相册 / 文件管理器的直觉一致。
     * 可在「设置 → 浏览排序方式」里改成其它方式。
     */
    public static final BrowseSort DEFAULT = TIME_DESC;

    public final String label;

    BrowseSort(String label) {
        this.label = label;
    }

    /** 从保存的字符串还原；未设置 / 值异常时回落到 {@link #DEFAULT}。 */
    public static BrowseSort fromName(String name) {
        if (name != null) {
            for (BrowseSort sort : values()) {
                if (sort.name().equals(name)) return sort;
            }
        }
        return DEFAULT;
    }

    /** 给设置页 Spinner 用的选项文案，顺序与 {@link #values()} 一致。 */
    public static String[] labels() {
        BrowseSort[] sorts = values();
        String[] out = new String[sorts.length];
        for (int i = 0; i < sorts.length; i++) out[i] = sorts[i].label;
        return out;
    }

    public boolean byTime() {
        return this == TIME_ASC || this == TIME_DESC;
    }

    public boolean descending() {
        return this == NAME_DESC || this == TIME_DESC;
    }

    /** 点击已选中的排序方式时切换升降序，点击另一种则换到该方式的默认方向。 */
    public BrowseSort toggle() {
        switch (this) {
            case NAME_ASC:
                return NAME_DESC;
            case NAME_DESC:
                return NAME_ASC;
            case TIME_ASC:
                return TIME_DESC;
            default:
                return TIME_ASC;
        }
    }

    /** 按当前方式对给定列表排序（原地排序）。 */
    public void sort(List<BrowseEntry> entries) {
        if (entries == null || entries.isEmpty()) return;

        final Comparator<BrowseEntry> nameAsc = new Comparator<BrowseEntry>() {
            @Override
            public int compare(BrowseEntry a, BrowseEntry b) {
                return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
            }
        };

        final Comparator<BrowseEntry> fileComparator;
        if (byTime()) {
            final int dir = descending() ? -1 : 1;
            fileComparator = new Comparator<BrowseEntry>() {
                @Override
                public int compare(BrowseEntry a, BrowseEntry b) {
                    if (a.mtimeMs == b.mtimeMs) return nameAsc.compare(a, b);
                    return a.mtimeMs < b.mtimeMs ? -dir : dir;
                }
            };
        } else {
            final int dir = descending() ? -1 : 1;
            fileComparator = new Comparator<BrowseEntry>() {
                @Override
                public int compare(BrowseEntry a, BrowseEntry b) {
                    return dir * nameAsc.compare(a, b);
                }
            };
        }

        Comparator<BrowseEntry> overall = new Comparator<BrowseEntry>() {
            @Override
            public int compare(BrowseEntry a, BrowseEntry b) {
                // 目录优先
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.dir ? nameAsc.compare(a, b) : fileComparator.compare(a, b);
            }
        };
        Collections.sort(entries, overall);
    }
}
