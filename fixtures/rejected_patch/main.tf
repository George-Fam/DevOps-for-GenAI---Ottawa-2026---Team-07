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

# yami-fixture: rejected-patch — this patch will break terraform validate
# The Surgeon will try to add a malformed block that terraform rejects
resource "aws_s3_bucket" "data" {
  bucket = "yami-rejected-bucket"
}
