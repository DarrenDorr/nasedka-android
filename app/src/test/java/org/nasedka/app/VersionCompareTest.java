package org.nasedka.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VersionCompareTest {
    @Test public void numericVersionComparison(){
        assertTrue(VersionCompare.compare("v0.3.0","0.2.0-dev")>0);
        assertTrue(VersionCompare.compare("0.2.3","v0.2.10")<0);
        assertEquals(0,VersionCompare.compare("v1.2","1.2.0"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectMalformedRelease(){
        VersionCompare.compare("evil-release","1.0.0");
    }
    @Test(expected=IllegalArgumentException.class) public void rejectInvalidOverflow(){
        VersionCompare.compare("999999999.2.0","1.0.0");
    }
}
