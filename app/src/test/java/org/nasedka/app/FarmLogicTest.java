package org.nasedka.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;

public class FarmLogicTest {
    @Test public void moneyPrecisionIsExact() {
        assertEquals(15325L,FarmLogic.cents("153,25"));
        assertEquals(100L,FarmLogic.cents("1"));
        assertEquals("153.25 ₽",FarmLogic.rub(15325));
        try{FarmLogic.cents("3.999");fail();}catch(IllegalArgumentException expected){}
        try{FarmLogic.cents("-4");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void stockAndHeadsCannotBecomeNegative() {
        assertEquals(2.75,FarmLogic.newBalance(3.0,-0.25),0.001);
        assertEquals(6,FarmLogic.newHeadcount(8,-2));
        try{FarmLogic.newBalance(0.5,-0.6);fail();}catch(IllegalArgumentException expected){}
        try{FarmLogic.newHeadcount(1,-2);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void dailyDateIsLocalNotMilliseconds() {
        ZoneId z=ZoneId.of("Europe/Berlin");
        long started=LocalDate.of(2026,3,27).atStartOfDay(z).toInstant().toEpochMilli();
        assertEquals(LocalDate.of(2026,4,17),FarmLogic.dueDate(started,21,z));
        assertEquals(75,FarmLogic.percentagePart(15,20));
        try{FarmLogic.percentagePart(21,20);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void validationRejectsInfiniteAndExcessPrecision() {
        try{FarmLogic.quantity("NaN");fail();}catch(IllegalArgumentException expected){}
        try{FarmLogic.quantity("Infinity");fail();}catch(IllegalArgumentException expected){}
        try{FarmLogic.required("  ","название",80);fail();}catch(IllegalArgumentException expected){}
    }
}
