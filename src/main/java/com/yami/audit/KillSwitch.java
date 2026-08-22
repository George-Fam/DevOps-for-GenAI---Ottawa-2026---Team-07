package com.yami.audit;

import java.util.function.Function;

/**
 * Global kill switch: when YAMI_DISABLED=true, every write point (GithubAdapter opening a
 * PR or posting an escalation comment) must refuse before touching the repo. Intentionally
 * has no effect on read-only stages (Scanner/Investigator/Judge/Verifier) - the point is
 * to stop *writes*, not the whole pipeline, so audit/diagnostic output still flows.
 */
public final class KillSwitch {

    private KillSwitch() {}

    public static boolean isDisabled() {
        return isDisabled(System::getenv);
    }

    static boolean isDisabled(Function<String, String> env) {
        return "true".equalsIgnoreCase(env.apply("YAMI_DISABLED"));
    }

    public static void checkNotDisabled(String action) {
        if (isDisabled()) {
            throw new IllegalStateException("YAMI_DISABLED=true - refusing to " + action);
        }
    }
}
