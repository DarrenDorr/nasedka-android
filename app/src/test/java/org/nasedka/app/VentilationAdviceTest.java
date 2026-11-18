package org.nasedka.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class VentilationAdviceTest {
    private Batch nesushka(String species) {
        Batch b=new Batch();b.profileId="nesushka";b.species=species;return b;
    }

    @Test public void manufacturerDoesNotSupplyUnprovenCoolingTime() {
        Batch chicken=nesushka(Batch.CHICKEN);
        assertTrue(VentilationAdvice.exchange(chicken).contains("вентиляционным отверстиям"));
        String cooling=VentilationAdvice.cooling(chicken);
        assertTrue(cooling.contains("НЕ устанавливает"));
        assertTrue(cooling.contains("220 В"));
        assertTrue(cooling.contains("не путайте") || cooling.contains("Не путайте"));
        assertFalse(cooling.contains("15 минут"));
        assertFalse(cooling.contains("2 раза в день"));
        assertTrue(VentilationAdvice.source(chicken).contains("8.10"));
    }

    @Test public void waterfowlSprayingIsNotMisrepresentedAsCooling() {
        String duck=VentilationAdvice.cooling(nesushka(Batch.DUCK));
        assertTrue(duck.contains("орошение"));
        assertTrue(duck.contains("не то же самое"));
        assertTrue(VentilationAdvice.cooling(nesushka(Batch.GOOSE)).contains("орошение"));
        assertFalse(VentilationAdvice.cooling(nesushka(Batch.QUAIL)).contains("орошение"));
    }

    @Test public void otherIncubatorsDoNotInheritNesushkaInstructions() {
        Batch other=nesushka(Batch.CHICKEN);other.profileId="other";
        assertTrue(VentilationAdvice.exchange(other).contains("вашей моделью"));
        assertTrue(VentilationAdvice.cooling(other).contains("не задаёт время"));
        assertFalse(VentilationAdvice.source(other).contains("«Несушка», 2021"));
    }
}
