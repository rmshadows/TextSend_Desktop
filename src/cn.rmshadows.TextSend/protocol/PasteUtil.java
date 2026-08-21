package protocol;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

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

    public static BufferedImage getClipboardImage() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                return null;
            }
            Object data = clipboard.getData(DataFlavor.imageFlavor);
            if (data instanceof BufferedImage bi) {
                return bi;
            }
            if (data instanceof Image img) {
                return toBuffered(img);
            }
        } catch (Exception e) {
            System.out.println("Log: clipboard image read failed: " + e.getMessage());
        }
        return null;
    }

    public static BufferedImage toBuffered(Image img) {
        if (img == null) {
            return null;
        }
        if (img instanceof BufferedImage bi) {
            return bi;
        }
        int w = Math.max(1, img.getWidth(null));
        int h = Math.max(1, img.getHeight(null));
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return bi;
    }

    /** 写入系统剪贴板，不模拟 Ctrl+V（避免误贴进当前焦点）。 */
    public static boolean setClipboardImage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            ImageSelection sel = new ImageSelection(image);
            clipboard.setContents(sel, sel);
            return true;
        } catch (Exception e) {
            System.out.println("Log: clipboard image failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("mac");
    }

    private static final class ImageSelection implements Transferable, ClipboardOwner {
        private final Image image;

        ImageSelection(Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }

        @Override
        public void lostOwnership(Clipboard clipboard, Transferable contents) {
        }
    }
}
