package app;

/** IDEから直接実行するときの入口（JavaFXのランタイム検査を回避するため） */
public class Launcher {
    public static void main(String[] args) {
        javafx.application.Application.launch(MakeCodeBlankApp.class, args);
    }
}
