package com.yami.audit;

/**
 * Extension du KillSwitch pour permettre un déclenchement programmatique
 * (par exemple depuis TokenBudget).
 */
public class KillSwitch {

    private static volatile boolean triggered = false;

    private KillSwitch() {}

    public static boolean isDisabled() {
        return isDisabled(System::getenv) || triggered;
    }

    static boolean isDisabled(java.util.function.Function<String, String> env) {
        return "true".equalsIgnoreCase(env.apply("YAMI_DISABLED")) || triggered;
    }

    public static void checkNotDisabled(String action) {
        if (isDisabled()) {
            throw new IllegalStateException("YAMI_DISABLED=true (or triggered) - refusing to " + action);
        }
    }

    public static void trigger(String reason) {
        triggered = true;
        System.err.println("[KillSwitch] TRIGGERED: " + reason);
    }

    public static void reset() {
        triggered = false;
    }
}
