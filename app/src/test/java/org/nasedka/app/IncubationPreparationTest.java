package org.nasedka.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class IncubationPreparationTest {
    @Test public void nesushkaStepsAreManualAttributedAndCoverSafety() {
        assertEquals(8, IncubationPreparation.steps(true).size());
        assertTrue(IncubationPreparation.steps(true).stream()
                .anyMatch(step -> step.source.contains("4.1") && step.title.contains("безопасности")));
        assertTrue(IncubationPreparation.steps(true).stream()
                .anyMatch(step -> step.title.contains("Яйца")));
    }

    @Test public void genericGuideDoesNotBorrowWaterTableOrThermostatFromNesushka() {
        assertEquals(5, IncubationPreparation.steps(false).size());
        assertTrue(IncubationPreparation.steps(false).stream()
                .allMatch(step -> step.source.contains("Универсальный")));
        String note = IncubationPreparation.journalNote(false, "Модель-1");
        assertTrue(note.contains("не проверена"));
        assertFalse(note.contains("пункты 1.2"));
    }

    @Test public void preparationNotesAreClearlyUserReported() {
        String note = IncubationPreparation.journalNote(true, "63");
        assertTrue(note.contains("Пользователь отметил"));
        assertTrue(note.contains("вместимость 63"));
        assertTrue(note.contains("не автоматическая проверка"));
    }
}
