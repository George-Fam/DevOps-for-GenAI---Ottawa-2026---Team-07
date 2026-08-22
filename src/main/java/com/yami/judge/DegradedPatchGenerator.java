package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.ProposedPatch;
import com.yami.core.RiskContextPacket;

import java.util.Optional;

/**
 * 20:00 pivot fallback: fills a fixed S3 template instead of calling Bedrock. Produces the
 * same {@link ProposedPatch} contract (same resourceAddress, same replacementBlock shape)
 * behind the same {@link BedrockClient} interface, so Verifier and everything downstream
 * is unchanged - only the content producer differs. Only handles aws_s3_bucket resources
 * (the demo fixture's scope); anything else degrades to HUMAN_REVIEW since there's no
 * template for it - never fabricates a patch for a resource type it doesn't understand.
 */
public class DegradedPatchGenerator implements BedrockClient {

    private static final String TEMPLATE = """
        resource "aws_s3_bucket" "%1$s" {
          bucket = "%2$s"
        }

        resource "aws_s3_bucket_versioning" "%1$s" {
          bucket = aws_s3_bucket.%1$s.id
          versioning_configuration {
            status = "Enabled"
          }
        }

        resource "aws_s3_bucket_server_side_encryption_configuration" "%1$s" {
          bucket = aws_s3_bucket.%1$s.id
          rule {
            apply_server_side_encryption_by_default {
              sse_algorithm = "aws:kms"
            }
          }
        }""";

    @Override
    public Decision invoke(RiskContextPacket packet, String ruleId, String resourceAddress) {
        int dot = resourceAddress.indexOf('.');
        String type = dot < 0 ? "" : resourceAddress.substring(0, dot);
        String name = dot < 0 ? "" : resourceAddress.substring(dot + 1);

        if (!type.equals("aws_s3_bucket")) {
            return new Decision(Decision.DecisionType.HUMAN_REVIEW, ruleId,
                "degraded mode has no template for resource type \"" + type + "\"", null, null, false, true);
        }

        Optional<String> bucketName = findKnownBucketName(packet, resourceAddress);
        if (bucketName.isEmpty()) {
            return new Decision(Decision.DecisionType.HUMAN_REVIEW, ruleId,
                "degraded mode could not find a known bucket name for " + resourceAddress, null, null, false, true);
        }

        String replacementBlock = TEMPLATE.formatted(name, bucketName.get());
        String justification = "degraded mode: fixed S3 template adding versioning + KMS encryption";
        ProposedPatch patch = new ProposedPatch(resourceAddress, replacementBlock, justification);

        return new Decision(Decision.DecisionType.SAFE_FIX, ruleId, justification, null, patch, true, true);
    }

    private static Optional<String> findKnownBucketName(RiskContextPacket packet, String resourceAddress) {
        String prefix = resourceAddress + ".bucket = ";
        return packet.known().stream()
            .filter(k -> k.startsWith(prefix))
            .map(k -> k.substring(prefix.length()))
            .findFirst();
    }
}
