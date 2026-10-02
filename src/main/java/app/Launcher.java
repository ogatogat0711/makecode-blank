package app;

/** IDEから直接実行するときの入口（JavaFXのランタイム検査を回避するため） */
public class Launcher {

    static final String VRAM_PROPERTY = "prism.maxvram";
    static final String VRAM_DEFAULT = "1G";

    public static void main(String[] args) {
        if (System.getProperty(VRAM_PROPERTY) == null) {
            System.setProperty(VRAM_PROPERTY, VRAM_DEFAULT);
        }
        javafx.application.Application.launch(MakeCodeBlankApp.class, args);
    }
}
