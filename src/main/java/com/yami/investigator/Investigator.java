package com.yami.investigator;

import com.yami.core.Finding;
import com.yami.core.RiskContextPacket;

import java.nio.file.Path;
import java.util.List;

public class Investigator {

    public RiskContextPacket buildPacket(String resourceAddress, List<Finding> findings, Path terraformDir) {
        throw new UnsupportedOperationException(
            "read-only, no network call. extract known/unknown facts about the resource, hash the packet. "
            + "raw repo content must never leave this method");
    }
}
