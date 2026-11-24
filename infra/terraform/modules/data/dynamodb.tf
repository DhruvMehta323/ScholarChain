# 15 on-demand tables. Key schemas: modules/storage/STORAGE-CONTRACTS.md section 4 (pk string; sk string where stated;
# TTL attribute expiresAt on QuerySession and EntityLinkCache). Physical names equal the defaults of scholarchain-storage.v1.conf.

locals {
  tables = {
    # operational tables
    "RunLedger"        = { sk = true, ttl = false }
    "ArtifactRegistry" = { sk = true, ttl = false }
    "QuerySession"     = { sk = false, ttl = true }
    "EntityLinkCache"  = { sk = true, ttl = true }
    "EvidenceBundle"   = { sk = true, ttl = false }
    # embedding key claims (accepted, main 765f7ce; STORAGE-CONTRACTS 12.4a): pk only, no TTL, permanent
    "embedding-key-claims" = { sk = false, ttl = false }
    # separate claims table for live suites / worker-image runs with indexOverride (STORAGE-CONTRACTS 12.4a); same schema
    "it-embedding-key-claims" = { sk = false, ttl = false }
    # analytics item tables
    "analytics-researcher-metrics"  = { sk = true, ttl = false }
    "analytics-coauthor-edge-works" = { sk = true, ttl = false }
    "analytics-bridges"             = { sk = true, ttl = false }
    "analytics-coreness"            = { sk = true, ttl = false }
    "analytics-temporal-changes"    = { sk = true, ttl = false }
    "analytics-paths"               = { sk = true, ttl = false }
    "analytics-path-estimates"      = { sk = false, ttl = false }
    "analytics-window-summary"      = { sk = false, ttl = false }
    "analytics-identity-clusters"   = { sk = false, ttl = false }
    "analytics-mention-researcher"  = { sk = false, ttl = false }
  }
}

locals {
  # Disposable test table: deletion protection off so cleanup needs no extra flip. It holds only live-suite claims.
  unprotected_tables = ["it-embedding-key-claims"]
}

# Justified exception AWS-0024: PITR is off by HUMAN DECISION for the course environment. Accepted loss, stated plainly: the analytics tables and
# EntityLinkCache/QuerySession are rebuilt from S3 and disposable, but RunLedger (audit trail), EvidenceBundle (audited answers) and the embedding
# claims tables (permanent key claims) are NOT rebuilt from S3. Without PITR an accidental delete or overwrite of an item in those tables cannot
# be restored; the only protections are deletion protection (table level), the conditional-write rules of the application and the short project
# life. variable dynamodb_pitr turns PITR on (about $0.20 per GB-month, not price-checked).
#trivy:ignore:AWS-0024
resource "aws_dynamodb_table" "this" {
  for_each                    = local.tables
  name                        = "${var.dynamodb_table_prefix}-${each.key}"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "pk"
  range_key                   = each.value.sk ? "sk" : null
  deletion_protection_enabled = contains(local.unprotected_tables, each.key) ? false : var.dynamodb_deletion_protection

  attribute {
    name = "pk"
    type = "S"
  }

  dynamic "attribute" {
    for_each = each.value.sk ? [1] : []
    content {
      name = "sk"
      type = "S"
    }
  }

  dynamic "ttl" {
    for_each = each.value.ttl ? [1] : []
    content {
      attribute_name = "expiresAt"
      enabled        = true
    }
  }

  point_in_time_recovery {
    enabled = var.dynamodb_pitr
  }

  # Encryption: DynamoDB default (AWS-owned key), no KMS cost.
  tags = { Name = "${var.dynamodb_table_prefix}-${each.key}" }
}
