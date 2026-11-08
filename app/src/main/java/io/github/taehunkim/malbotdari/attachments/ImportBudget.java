package io.github.taehunkim.malbotdari.attachments;

/** Tracks a share's limits using actual copied bytes rather than provider-reported sizes. */
public final class ImportBudget {
    public static final int MAX_FILES = 10;
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    private int files;
    private long bytes;

    public boolean hasSlot() { return files < MAX_FILES; }
    public long nextLimit() { return hasSlot() ? Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - bytes) : 0; }
    public void added(long count) {
        if (!hasSlot() || count < 0 || count > nextLimit()) throw new IllegalArgumentException("Import budget exceeded");
        files++;
        bytes += count;
    }
}
