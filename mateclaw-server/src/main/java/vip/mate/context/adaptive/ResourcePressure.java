package vip.mate.context.adaptive;

public enum ResourcePressure {
    NORMAL(0), ELEVATED(1), HIGH(2), CRITICAL(3);
    private final int level;
    ResourcePressure(int level) { this.level = level; }
    public int level() { return level; }
    public boolean atLeast(ResourcePressure o) { return this.level >= o.level; }
    public ResourcePressure escalate() { return switch(this){ case NORMAL->ELEVATED; case ELEVATED->HIGH; default->CRITICAL; }; }
    public int recoveryThreshold() { return switch(this){ case CRITICAL->20; case HIGH->10; case ELEVATED->5; default->0; }; }
}
