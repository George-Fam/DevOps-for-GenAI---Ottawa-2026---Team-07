package com.yami.verifier;

import com.yami.core.ProposedPatch;
import com.yami.core.VerificationResult;

import java.nio.file.Path;

public class Verifier {

    private final HclBlockReplacer blockReplacer;

    public Verifier(HclBlockReplacer blockReplacer) {
        this.blockReplacer = blockReplacer;
    }

    public VerificationResult verify(Path repoDir, ProposedPatch patch) {
        throw new UnsupportedOperationException(
            "disposable branch -> block replacement -> terraform fmt -> terraform validate -> re-scan -> "
            + "before/after finding comparison. never trust patch content before this runs. "
            + "on any failure: rejection, never a silent retry, never a write");
    }
}
