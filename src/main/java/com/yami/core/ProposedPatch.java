package com.yami.core;

public record ProposedPatch(
    String resourceAddress,
    String replacementBlock,
    String justification
) {}
