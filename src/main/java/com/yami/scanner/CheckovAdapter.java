package com.yami.scanner;

import com.yami.core.Finding;

import java.nio.file.Path;
import java.util.List;

public class CheckovAdapter {

    public List<Finding> scan(Path terraformDir) {
        throw new UnsupportedOperationException("invoke checkov as a subprocess, parse its JSON output into Finding records");
    }
}
