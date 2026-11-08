package io.github.taehunkim.malbotdari.attachments;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** Copies an incoming stream to a new private file while enforcing the remaining byte budget. */
public final class BoundedImport {
    private BoundedImport() {}

    public static final class TooLarge extends IOException {
        public TooLarge() { super("Attachment exceeds the available byte limit"); }
    }

    /** The caller owns and closes input. A failed copy removes the file this call created. */
    public static long copy(InputStream input, File target, long maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("Negative byte limit");
        if (!target.createNewFile()) throw new IOException("Destination already exists");
        boolean complete = false;
        try {
            long total = 0;
            try (FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
                    int count = input.read(buffer);
                    if (count == -1) break;
                    if (count == 0) continue;
                    if (count > maxBytes - total) throw new TooLarge();
                    output.write(buffer, 0, count);
                    total += count;
                }
            }
            complete = true;
            return total;
        } finally {
            if (!complete) target.delete();
        }
    }
}
