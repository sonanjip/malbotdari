package io.github.taehunkim.malbotdari.attachments;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class BoundedImportTest {
    @Test public void copiesExactlyAtLimit() throws Exception {
        File dir = Files.createTempDirectory("import-test").toFile();
        File target = new File(dir, "item");
        assertEquals(3, BoundedImport.copy(new ByteArrayInputStream(new byte[]{1,2,3}), target, 3));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(target.toPath()));
    }

    @Test public void oversizeDeletesPartialFile() throws Exception {
        File dir = Files.createTempDirectory("import-test").toFile();
        File target = new File(dir, "item");
        try { BoundedImport.copy(new ByteArrayInputStream(new byte[4]), target, 3); fail(); }
        catch (BoundedImport.TooLarge expected) { assertFalse(target.exists()); }
    }

    @Test public void failedStreamDeletesPartialFile() throws Exception {
        File dir = Files.createTempDirectory("import-test").toFile();
        File target = new File(dir, "item");
        InputStream bad = new InputStream() {
            int count;
            @Override public int read() throws IOException { if (count++ == 0) return 1; throw new IOException("revoked"); }
        };
        try { BoundedImport.copy(bad, target, 10); fail(); }
        catch (IOException expected) { assertFalse(target.exists()); }
    }

    @Test public void existingFileIsNeverOverwritten() throws Exception {
        File dir = Files.createTempDirectory("import-test").toFile();
        File target = new File(dir, "item");
        Files.write(target.toPath(), new byte[]{9});
        try { BoundedImport.copy(new ByteArrayInputStream(new byte[]{1}), target, 3); fail(); }
        catch (IOException expected) { assertArrayEquals(new byte[]{9}, Files.readAllBytes(target.toPath())); }
    }
}
