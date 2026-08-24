package application;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** GNOME 任务栏：固定 WM_CLASS=TextSend，与 .desktop 的 StartupWMClass 对齐。 */
final class LinuxWmClass {
    static final String WM_CLASS = "TextSend";

    private LinuxWmClass() {
    }

    /** 在首帧创建前调用（main 第一行）。 */
    static void initIfNeeded() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return;
        }
        try {
            Toolkit tk = Toolkit.getDefaultToolkit();
            Field f = tk.getClass().getDeclaredField("awtAppClassName");
            f.setAccessible(true);
            if (Modifier.isStatic(f.getModifiers())) {
                f.set(null, WM_CLASS);
            } else {
                f.set(tk, WM_CLASS);
            }
        } catch (Exception ignored) {
            // IDE 未加 --add-opens 时跳过；deb/jpackage 启动参数里已加
        }
    }
}
