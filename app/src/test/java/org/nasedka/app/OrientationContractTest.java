package org.nasedka.app;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Manifest contract: rotation must not destroy unfinished incubation dialogs. */
public class OrientationContractTest {
    @Test public void mainActivityKeepsDialogsDuringRotationWithoutLockingScreen() throws Exception {
        File file = new File("src/main/AndroidManifest.xml");
        assertTrue("Missing source manifest: " + file.getAbsolutePath(), file.isFile());
        Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList nodes = xml.getElementsByTagName("activity");
        Element main = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            if (".MainActivity".equals(item.getAttribute("android:name"))) {
                main = item;
                break;
            }
        }
        assertNotNull("Missing MainActivity", main);
        Set<String> flags = new HashSet<>(Arrays.asList(
                main.getAttribute("android:configChanges").split("\\|")));
        for (String flag : Arrays.asList("orientation", "screenSize", "smallestScreenSize", "screenLayout")) {
            assertTrue("Missing handled change: " + flag, flags.contains(flag));
        }
        assertFalse("Do not lock screen orientation as a workaround",
                main.hasAttribute("android:screenOrientation"));
    }
}
