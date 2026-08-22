terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-east-1"
}

# yami-fixture: quick_test - a single bucket with a handful of Checkov findings
# (versioning/encryption/logging/replication/lifecycle/notifications), same
# SAFE_FIX-eligible shape as fixtures/safe_fix. Scanned only via the
# yami-quick-test workflow_dispatch workflow (scope override), never on
# pull_request/main, so a full pipeline run stays fast without touching the
# other fixtures' 74 findings.
resource "aws_s3_bucket" "data" {
  bucket = "yami-quick-test-bucket"
}

resource "aws_s3_bucket_public_access_block" "data" {
  bucket                  = aws_s3_bucket.data.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
