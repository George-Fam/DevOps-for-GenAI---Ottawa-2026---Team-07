package com.yami.scanner;

import com.yami.core.Finding;

import java.nio.file.Path;
import java.util.List;

public class CicdRules {

    public List<Finding> evaluate(Path terraformDir) {
        throw new UnsupportedOperationException("hand-written CI/CD-specific rules not covered by checkov");
    }
}
