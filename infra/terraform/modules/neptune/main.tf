# Neptune Serverless: one cluster, one db.serverless instance, minimum capacity range, private subnets, encrypted.
# Reached only from query-api, retrieval and EMR security groups (see the network module).
# Cost note: Neptune Serverless does not scale to zero; set enabled = false (var create_neptune) when the graph is not needed.

locals {
  count_enabled = var.enabled ? 1 : 0
}

resource "aws_neptune_subnet_group" "this" {
  count       = local.count_enabled
  name        = "${var.name}-neptune"
  description = "Private subnets for Neptune"
  subnet_ids  = var.private_subnet_ids
}

resource "aws_neptune_cluster" "this" {
  count                               = local.count_enabled
  cluster_identifier                  = "${var.name}-neptune"
  engine                              = "neptune"
  engine_version                      = var.engine_version
  neptune_subnet_group_name           = aws_neptune_subnet_group.this[0].name
  vpc_security_group_ids              = [var.security_group_id]
  storage_encrypted                   = true # AWS-owned/managed key, no customer-managed KMS key
  iam_database_authentication_enabled = true
  backup_retention_period             = 1
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = var.skip_final_snapshot
  final_snapshot_identifier           = var.skip_final_snapshot ? null : "${var.name}-neptune-final"
  apply_immediately                   = true
  iam_roles                           = [aws_iam_role.loader[0].arn] # bulk-load role attached to the cluster

  serverless_v2_scaling_configuration {
    min_capacity = var.min_capacity
    max_capacity = var.max_capacity
  }
}

resource "aws_neptune_cluster_instance" "this" {
  count                      = local.count_enabled
  identifier                 = "${var.name}-neptune-1"
  cluster_identifier         = aws_neptune_cluster.this[0].id
  engine                     = "neptune"
  instance_class             = "db.serverless"
  neptune_subnet_group_name  = aws_neptune_subnet_group.this[0].name
  auto_minor_version_upgrade = false
  apply_immediately          = true
  publicly_accessible        = false
}

############ Bulk-load role (separate from every runtime role; read-only on the load prefix) ############

data "aws_iam_policy_document" "loader_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "loader" {
  statement {
    sid       = "ReadLoadFiles"
    actions   = ["s3:GetObject"]
    resources = ["${var.curated_bucket_arn}/${var.load_prefix}*"]
  }
  statement {
    sid       = "ListLoadPrefix"
    actions   = ["s3:ListBucket"]
    resources = [var.curated_bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.load_prefix}*"]
    }
  }
}

resource "aws_iam_role" "loader" {
  count              = local.count_enabled
  name               = "${var.name}-neptune-load"
  description        = "Assumed by Neptune to bulk-load openCypher CSV from the graph load prefix"
  assume_role_policy = data.aws_iam_policy_document.loader_trust.json
}

resource "aws_iam_role_policy" "loader" {
  count  = local.count_enabled
  name   = "read-load-prefix"
  role   = aws_iam_role.loader[0].id
  policy = data.aws_iam_policy_document.loader.json
}
