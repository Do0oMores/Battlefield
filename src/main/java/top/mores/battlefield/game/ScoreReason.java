package top.mores.battlefield.game;

public enum ScoreReason {
    CAPTURE("占领", 0xFF88FF88, "CAPTURE"),
    DEFEND("防守",0xFF88FF88,"DEFEND"),
    DAMAGE("命中",  0xFF9AD0FF, "DAMAGE"),
    KILL("击杀",    0xFFFFD54A, "KILL"),
    STREAK("连杀",  0xFFFFB74D, "STREAK"),
    SQUAD_WIPE("小队团灭", 0xFFFF6B6B, "SQUAD_WIPE");

    public final String text;
    public final int color;
    public final String mergeKey;

    ScoreReason(String text, int color, String mergeKey) {
        this.text = text;
        this.color = color;
        this.mergeKey = mergeKey;
    }
}
