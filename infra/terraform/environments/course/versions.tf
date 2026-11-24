terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  # Local state on purpose (documented decision, INFRA-CONTRACTS.md section Foundation):
  # a disposable single-person course environment, no remote backend to bootstrap.
  # terraform.tfstate is gitignored and must be kept by the human (it is the only record of what exists).

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 6.73.0 is the latest release listed by registry.terraform.io (checked 2026-11-22);
      # whether it contains aws_s3vectors_* is verified in INFRA-CONTRACTS.md from the provider schema.
      version = "~> 6.73"
    }
  }
}
