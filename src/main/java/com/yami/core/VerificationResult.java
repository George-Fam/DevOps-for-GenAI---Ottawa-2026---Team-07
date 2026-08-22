package com.yami.core;

import java.util.List;

public record VerificationResult(
    StepResult terraformFormat,
    StepResult terraformValidate,
    StepResult rescan,
    List<Finding> newFindings,
    String remediationPr
) {
    public enum StepResult { PASS, FAIL, NOT_RUN, NOT_VERIFIED }

    public boolean passed() {
        return terraformFormat == StepResult.PASS
            && terraformValidate == StepResult.PASS
            && rescan == StepResult.PASS;
    }
}
