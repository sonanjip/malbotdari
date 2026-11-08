package io.github.taehunkim.malbotdari.attachments;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImportBudgetTest {
    @Test public void acceptsAtMostTenFiles() {
        ImportBudget budget = new ImportBudget();
        for (int i = 0; i < 10; i++) { assertTrue(budget.hasSlot()); budget.added(1); }
        assertFalse(budget.hasSlot());
    }
    @Test public void limitsEachFileAndTheTotal() {
        ImportBudget budget = new ImportBudget();
        assertEquals(100L * 1024 * 1024, budget.nextLimit());
        budget.added(100L * 1024 * 1024);
        budget.added(99L * 1024 * 1024);
        assertEquals(1024 * 1024, budget.nextLimit());
        budget.added(1024 * 1024);
        assertTrue(budget.hasSlot());
        assertEquals(0, budget.nextLimit());
        budget.added(0);
    }
    @Test public void doesNotAcceptMoreThanTheRemainingBudget() {
        ImportBudget budget = new ImportBudget();
        try { budget.added(ImportBudget.MAX_FILE_BYTES + 1); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(ImportBudget.MAX_FILE_BYTES, budget.nextLimit()); }
    }
}
