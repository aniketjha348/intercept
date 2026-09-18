terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  # Remote state (bootstrap once, see docs/AWS_DEPLOY.md).
  backend "s3" {}
}
