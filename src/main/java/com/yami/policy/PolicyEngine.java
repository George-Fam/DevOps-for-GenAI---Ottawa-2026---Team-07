package com.yami.policy;

import com.yami.core.Decision;
import com.yami.core.Finding;

import java.util.List;

public class PolicyEngine {

    public PolicyEngine(java.nio.file.Path policyFile) {
        throw new UnsupportedOperationException("load policies/yami.yml");
    }

    public Decision.Outcome classify(List<Finding> findings) {
        throw new UnsupportedOperationException("deterministic gate: findings + policy thresholds -> SAFE_FIX / HUMAN_REVIEW / BLOCK");
    }
}
