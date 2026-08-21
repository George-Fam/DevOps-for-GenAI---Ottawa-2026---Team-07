package com.yami.github;

import com.yami.core.ProposedPatch;

public class GithubAdapter {

    public void openRemediationPr(ProposedPatch patch, String branchName) {
        throw new UnsupportedOperationException("push disposable branch, open PR via gh CLI or GitHub REST API, scoped GITHUB_TOKEN only");
    }

    public void escalateToHumanReview(String reason) {
        throw new UnsupportedOperationException("comment on the PR / label HUMAN_REVIEW, write nothing to the repo");
    }
}
