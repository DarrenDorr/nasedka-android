package org.nasedka.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class IncubationRecordsTest {
    @Test public void recordsDoNotAssumeUnknownFailures() {
        String value=IncubationRecords.hatch(30,23,null,null,null,"");
        assertTrue(value.contains("Вывелось: 23"));
        assertTrue(value.contains("Не распределено по указанным категориям: 7"));
        assertTrue(value.contains("не записано"));
    }
    @Test public void rejectsImpossibleCandlingCounts() {
        try{IncubationRecords.candling(20,15,12,10,0,1,"");fail();}
        catch(IllegalArgumentException expected){}
        assertTrue(IncubationRecords.candling(20,15,7,3,5,2,"")
                .contains("Сомнительные: 5"));
    }
    @Test public void rejectsOvercountAndPreservesUserNote() {
        try{IncubationRecords.hatch(8,6,3,null,null,"");fail();}
        catch(IllegalArgumentException expected){}
        assertTrue(IncubationRecords.hatch(8,6,1,0,0,"Подготовил брудер")
                .contains("Подготовил брудер"));
    }
}
