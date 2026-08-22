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

# yami-fixture: human_review - same missing versioning/encryption gaps as safe_fix, but
# this bucket is a CloudFront origin. The no_cloudfront_relation policy condition is false,
# so PolicyEngine routes CLOUD-001 to HUMAN_REVIEW instead of SAFE_FIX - a mechanical patch
# risks breaking the CDN's origin access configuration, so a human should look at it.
resource "aws_s3_bucket" "site" {
  bucket = "yami-demo-site-assets"
}

resource "aws_cloudfront_origin_access_control" "site" {
  name                              = "yami-demo-site-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "site" {
  enabled = true

  origin {
    domain_name              = aws_s3_bucket.site.bucket_regional_domain_name
    origin_id                = "s3-site-origin"
    origin_access_control_id = aws_cloudfront_origin_access_control.site.id
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-site-origin"
    viewer_protocol_policy = "redirect-to-https"

    forwarded_values {
      query_string = false
      cookies {
        forward = "none"
      }
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
