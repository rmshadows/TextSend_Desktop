package protocol;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;

public final class PasteUtil {
    private PasteUtil() {
    }

    public static void pasteText(String text) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
            Robot robot = new Robot();
            robot.delay(40);
            int modifier = isMac() ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
            robot.keyPress(modifier);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(modifier);
        } catch (Exception e) {
            System.out.println("Log: auto-paste skipped: " + e.getMessage());
        }
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("mac");
    }
}
