package org.nasedka.app;

/** Strict comparison of stable numeric release tags, such as v1.2.3. */
public final class VersionCompare {
    private VersionCompare() {}
    public static int compare(String a, String b) {
        int[] left = numbers(a), right = numbers(b);
        for (int i=0; i<3; i++) {
            if (left[i] != right[i]) return Integer.compare(left[i], right[i]);
        }
        return 0;
    }
    private static int[] numbers(String version) {
        if (version == null) throw new IllegalArgumentException("Missing version");
        String v=version.trim().replaceFirst("^[vV]", "").replaceFirst("-dev$", "");
        if (!v.matches("[0-9]{1,7}\\.[0-9]{1,7}(\\.[0-9]{1,7})?")) throw new IllegalArgumentException("Unsupported version");
        String[] parts=v.split("\\.");
        return new int[]{Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),parts.length==3?Integer.parseInt(parts[2]):0};
    }
}
