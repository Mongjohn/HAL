package android.util;

/** JVM tests exercise expected HA failures without an Android logcat service. */
public final class Log {
    public static int w(String tag, String message) { return 0; }
    public static boolean isLoggable(String tag, int priority) { return false; }
    public static int println(int priority, String tag, String message) { return 0; }
}
