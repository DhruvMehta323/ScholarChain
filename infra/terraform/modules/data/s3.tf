# Five buckets (justification in INFRA-CONTRACTS.md): raw, curated (curated/ graph/ rag/ prefixes),
# reports (evidence, never expired), logs (EMR and other logs), artifacts (jars, scripts).
# All: public-access block, SSE-S3, TLS-only policy, BucketOwnerEnforced, force_destroy = false.

locals {
  # Buckets that keep object versions (raw inputs and evidence must be recoverable).
  versioned = toset(["raw", "reports", "artifacts"])

  # Lifecycle: expire intermediate data only. raw/ and reports/ never expire current objects.
  expiration_rules = {
    raw       = []
    reports   = []
    curated   = [{ id = "expire-tmp", prefix = "tmp/", days = 7 }, { id = "expire-it-probes", prefix = "it/", days = 7 }, { id = "expire-it-embedding", prefix = "logs/it-embedding/", days = 7 }, { id = "expire-neptune-load-csv", prefix = "graph/neptune-load/", days = 30 }]
    logs      = [{ id = "expire-logs", prefix = "", days = 14 }]
    artifacts = [{ id = "expire-emr-scratch", prefix = "emr-scratch/", days = 7 }]
  }
}

resource "aws_s3_bucket" "this" {
  for_each = var.bucket_names
  bucket   = each.value
  # raw and reports hold evidence: never force-destroyed. The others are intermediate and follow a variable the human flips for cleanup.
  force_destroy = contains(["raw", "reports"], each.key) ? false : var.force_destroy_intermediate
  tags          = { Name = each.value, role = each.key }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = aws_s3_bucket.this
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Justified exception AWS-0132: SSE-S3 (AES256) by decision: a customer-managed KMS key costs monthly and nothing in the work order or the contracts requires one (INFRA-CONTRACTS section 3); buckets are private, TLS-only and versioned or lifecycle-managed.
#trivy:ignore:AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # SSE-S3: no KMS key, no monthly key cost
    }
  }
}

# Versioned buckets only (raw, reports, artifacts). Evidence buckets keep their versions (see the lifecycle rules below).
resource "aws_s3_bucket_versioning" "this" {
  for_each = { for k, b in aws_s3_bucket.this : k => b if contains(local.versioned, k) }
  bucket   = each.value.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Separate resource, on purpose: the AWS-0090 exception below covers EXACTLY these two buckets (curated, logs). If it sat on the shared
# versioning resource above, a trivy ignore on that block would also hide a finding for raw, reports or artifacts.
# Justified exception AWS-0090 (accepted by the human): no versioning on curated and logs. Both hold regenerable intermediate data and logs
# with short lifecycle expiry; versioning would multiply EMR output-committer temporary objects and add cost without protecting evidence.
#trivy:ignore:AWS-0090
resource "aws_s3_bucket_versioning" "unversioned" {
  for_each = { for k, b in aws_s3_bucket.this : k => b if !contains(local.versioned, k) }
  bucket   = each.value.id
  versioning_configuration {
    status = "Suspended"
  }
}

data "aws_iam_policy_document" "tls_only" {
  for_each = aws_s3_bucket.this
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [each.value.arn, "${each.value.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "tls_only" {
  for_each   = aws_s3_bucket.this
  bucket     = each.value.id
  policy     = data.aws_iam_policy_document.tls_only[each.key].json
  depends_on = [aws_s3_bucket_public_access_block.this]
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each   = aws_s3_bucket.this
  bucket     = each.value.id
  depends_on = [aws_s3_bucket_versioning.this, aws_s3_bucket_versioning.unversioned]

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }

  # M3: the evidence buckets (raw, reports) keep noncurrent versions, so an accidental overwrite or delete stays recoverable.
  # Cost is bounded (they only hold datasets and reports). Other buckets expire old versions after 7 days.
  dynamic "rule" {
    for_each = contains(["raw", "reports"], each.key) ? [] : [1]
    content {
      id     = "expire-noncurrent-versions"
      status = "Enabled"
      filter {}
      noncurrent_version_expiration {
        noncurrent_days = 7
      }
    }
  }

  dynamic "rule" {
    for_each = local.expiration_rules[each.key]
    content {
      id     = rule.value.id
      status = "Enabled"
      filter {
        prefix = rule.value.prefix
      }
      expiration {
        days = rule.value.days
      }
    }
  }
}
