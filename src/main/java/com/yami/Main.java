package com.yami;

public class Main {

    public static void main(String[] args) {
        throw new UnsupportedOperationException(
            "wire the pipeline: Scanner -> Investigator -> Judge -> Verifier -> AuditStore + GithubAdapter. "
            + "read PR event context from GITHUB_EVENT_PATH / action inputs");
    }
}
