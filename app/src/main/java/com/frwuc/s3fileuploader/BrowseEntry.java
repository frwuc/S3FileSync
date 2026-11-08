package com.frwuc.s3fileuploader;

/**
 * 文件夹浏览列表中的一行：本地文件/文件夹，或七牛空间里的文件/目录前缀。
 *
 * <p>本地与云端共用同一模型，浏览对话框只关心「是不是目录、叫什么、多大、什么时候的」，
 * 排序与展示因此可以完全复用。
 */
public class BrowseEntry {

    /** 展示名（本地为文件名，云端为 key 的最后一段） */
    public final String name;
    /** 本地为绝对路径，云端为完整 key（目录为以 "/" 结尾的前缀） */
    public final String path;
    public final boolean dir;
    /** 字节数；目录为 0 */
    public final long size;
    /** 修改时间（毫秒）；云端取 putTime，目录为 0 */
    public final long mtimeMs;

    public BrowseEntry(String name, String path, boolean dir, long size, long mtimeMs) {
        this.name = name;
        this.path = path;
        this.dir = dir;
        this.size = size;
        this.mtimeMs = mtimeMs;
    }

    public boolean isFile() {
        return !dir;
    }
}
