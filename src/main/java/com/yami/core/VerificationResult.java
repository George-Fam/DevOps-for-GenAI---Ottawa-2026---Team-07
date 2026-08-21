package com.yami.core;

import java.util.List;

public record VerificationResult(
    boolean passed,
    boolean formatValid,
    boolean validateValid,
    List<Finding> findingsBefore,
    List<Finding> findingsAfter,
    String rejectionReason
) {}
