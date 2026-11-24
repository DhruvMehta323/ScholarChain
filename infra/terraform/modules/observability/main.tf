# Alarms, one dashboard, an SNS topic for notifications, and the AWS Budgets budget.

locals {
  has_alb       = var.alb_enabled
  alarm_actions = [aws_sns_topic.alerts.arn]
}

# Justified exception AWS-0095 (human decision, round 2): the topic is NOT encrypted. It carries CloudWatch alarm notifications only
# (alarm name and state text, no data). Encrypting it with the AWS-managed key alias/aws/sns is expected to stop CloudWatch alarms from
# publishing, because that key's policy cannot be edited to allow cloudwatch.amazonaws.com (see INFRA-CONTRACTS.md section 7; the
# documented key-policy statement is at https://docs.aws.amazon.com/sns/latest/dg/sns-key-management.html; the failure itself is NOT VERIFIED).
# AWS Budgets does not use this topic (it emails its subscribers directly).
#trivy:ignore:AWS-0095
resource "aws_sns_topic" "alerts" {
  name = "${var.name}-alerts"
}

resource "aws_sns_topic_policy" "alerts" {
  arn    = aws_sns_topic.alerts.arn
  policy = data.aws_iam_policy_document.alerts_topic.json
}

data "aws_iam_policy_document" "alerts_topic" {
  statement {
    sid       = "AllowCloudWatchAlarms"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alert_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  count               = local.has_alb ? 1 : 0
  alarm_name          = "${var.name}-query-api-5xx"
  alarm_description   = "query-api returned 5xx responses"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "dlq_depth" {
  alarm_name          = "${var.name}-embedding-dlq-depth"
  alarm_description   = "Messages in the embedding dead-letter queue"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.dlq_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "queue_age" {
  alarm_name          = "${var.name}-embedding-queue-age"
  alarm_description   = "Oldest embedding message older than the threshold"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = var.queue_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.queue_age_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = "${var.name}-overview"
  dashboard_body = jsonencode({
    widgets = concat(
      [
        {
          type = "metric", x = 0, y = 0, width = 12, height = 6
          properties = {
            title  = var.container_insights_enabled ? "ECS desired and running tasks (Container Insights)" : "ECS CPU and memory per service (AWS/ECS; desired/running counts need Container Insights)"
            region = var.region
            view   = "timeSeries"
            stat   = "Average"
            period = 60
            # concat(... expansion) keeps each metric as its own array; flatten() would collapse them into one list of strings.
            metrics = var.container_insights_enabled ? concat([for s in var.service_names : [
              ["ECS/ContainerInsights", "DesiredTaskCount", "ClusterName", var.cluster_name, "ServiceName", s],
              ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.cluster_name, "ServiceName", s],
              ]]...) : concat([for s in var.service_names : [
              ["AWS/ECS", "CPUUtilization", "ClusterName", var.cluster_name, "ServiceName", s],
              ["AWS/ECS", "MemoryUtilization", "ClusterName", var.cluster_name, "ServiceName", s],
            ]]...)
          }
        },
        {
          type = "metric", x = 12, y = 0, width = 12, height = 6
          properties = {
            title  = "SQS depth and age (embedding queue and DLQ)"
            region = var.region
            view   = "timeSeries"
            stat   = "Maximum"
            period = 60
            metrics = [
              ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.queue_name],
              ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", var.queue_name],
              ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.dlq_name],
            ]
          }
        },
        {
          type = "metric", x = 12, y = 6, width = 12, height = 6
          properties = {
            title  = "DynamoDB errors and throttles (runtime tables, explicit metrics)"
            region = var.region
            view   = "timeSeries"
            period = 300
            stat   = "Sum"
            # Explicit metrics only (no SEARCH): the whole dashboard stays well under 50 metrics (free-dashboard condition).
            metrics = concat(
              [["AWS/DynamoDB", "UserErrors"]],
              [for t in values(var.dynamodb_runtime_tables) : ["AWS/DynamoDB", "ReadThrottleEvents", "TableName", t]],
              [for t in values(var.dynamodb_runtime_tables) : ["AWS/DynamoDB", "WriteThrottleEvents", "TableName", t]],
              [
                ["AWS/DynamoDB", "SystemErrors", "TableName", var.dynamodb_runtime_tables["QuerySession"], "Operation", "PutItem"],
                ["AWS/DynamoDB", "SystemErrors", "TableName", var.dynamodb_runtime_tables["EvidenceBundle"], "Operation", "PutItem"],
                ["AWS/DynamoDB", "SystemErrors", "TableName", var.dynamodb_runtime_tables["researcher-metrics"], "Operation", "GetItem"],
                ["AWS/DynamoDB", "SystemErrors", "TableName", var.dynamodb_runtime_tables["researcher-metrics"], "Operation", "Query"],
              ],
            )
          }
        },
      ],
      [for w in [
        {
          type = "metric", x = 0, y = 6, width = 12, height = 6
          properties = {
            title  = "API latency (p95) and 5xx"
            region = var.region
            view   = "timeSeries"
            period = 60
            metrics = [
              ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p95" }],
              ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum", yAxis = "right" }],
            ]
          }
        },
      ] : w if var.alb_enabled],
      [for w in [
        {
          type = "metric", x = 0, y = 12, width = 12, height = 6
          properties = {
            title  = "Neptune capacity and CPU"
            region = var.region
            view   = "timeSeries"
            stat   = "Average"
            period = 60
            metrics = [
              ["AWS/Neptune", "ServerlessDatabaseCapacity", "DBClusterIdentifier", var.neptune_cluster_identifier],
              ["AWS/Neptune", "CPUUtilization", "DBClusterIdentifier", var.neptune_cluster_identifier, { yAxis = "right" }],
            ]
          }
        },
        {
          type = "metric", x = 12, y = 12, width = 12, height = 6
          properties = {
            title  = "Neptune transaction errors"
            region = var.region
            view   = "timeSeries"
            stat   = "Sum"
            period = 60
            metrics = [
              ["AWS/Neptune", "NumTxRolledBack", "DBClusterIdentifier", var.neptune_cluster_identifier],
            ]
          }
        },
      ] : w if var.neptune_enabled]
    )
  })
}

resource "aws_budgets_budget" "project" {
  name         = "${var.name}-budget"
  budget_type  = "COST"
  limit_amount = tostring(var.budget_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Credits are EXCLUDED from the measured cost (human decision): the budget tracks what the account would pay without credits.
  # Everything is stated explicitly so provider defaults cannot change it. Unblended (not blended, not amortized); refunds are not
  # netted off (conservative); discounts, tax, support and subscriptions count. Budgets data is refreshed up to three times a day
  # (https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html, 2026-11-24), so alerts LAG real spend.
  cost_types {
    include_credit             = false
    include_discount           = true
    include_other_subscription = true
    include_recurring          = true
    include_refund             = false
    include_subscription       = true
    include_support            = true
    include_tax                = true
    include_upfront            = true
    use_amortized              = false
    use_blended                = false
  }

  dynamic "notification" {
    for_each = var.budget_alert_thresholds_usd
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "ABSOLUTE_VALUE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [var.budget_email]
    }
  }
}
