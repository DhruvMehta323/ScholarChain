# ECR repositories, the embedding queue with its dead-letter queue, and empty Secrets Manager entries.
# Terraform never holds a secret value: secrets are created without a version; the human sets the value.

resource "aws_ecr_repository" "this" {
  for_each             = toset(var.service_names)
  name                 = "${var.name}/${each.value}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.ecr_force_delete

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last ${var.ecr_keep_images} images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = var.ecr_keep_images }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_sqs_queue" "embedding_dlq" {
  name                      = var.dlq_name
  message_retention_seconds = 1209600 # 14 days
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "embedding" {
  name                       = var.queue_name
  visibility_timeout_seconds = var.embedding_visibility_timeout
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.embedding_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "embedding_dlq" {
  queue_url = aws_sqs_queue.embedding_dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.embedding.arn]
  })
}

# Empty secrets: names only. The value is set by a human outside Terraform and never appears in state or plan.
resource "aws_secretsmanager_secret" "this" {
  for_each                = var.secret_names
  name                    = each.value
  description             = "Value set by a human; Terraform holds no secret value."
  recovery_window_in_days = var.secret_recovery_window_days
}
