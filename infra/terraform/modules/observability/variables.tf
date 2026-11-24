variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "account_id" {
  type = string
}

variable "alert_email" {
  description = "Email for alarm notifications. Empty: no subscription (alarms still change state)."
  type        = string
  default     = ""
}

variable "budget_email" {
  type = string
}

variable "budget_limit_usd" {
  type = number
}

variable "budget_alert_thresholds_usd" {
  description = "Absolute actual-spend thresholds that send an email."
  type        = list(number)
}

variable "cluster_name" {
  type = string
}

variable "service_names" {
  type = list(string)
}

variable "queue_name" {
  type = string
}

variable "dlq_name" {
  type = string
}

variable "queue_age_threshold_seconds" {
  type    = number
  default = 600
}

variable "container_insights_enabled" {
  type    = bool
  default = false
}

variable "dynamodb_runtime_tables" {
  description = "Physical names of the tables the runtime writes or reads most (keys QuerySession, EvidenceBundle, researcher-metrics); charted with explicit metrics."
  type        = map(string)
}

variable "alb_enabled" {
  description = "Plan-time boolean: whether the load balancer exists."
  type        = bool
}

variable "alb_arn_suffix" {
  type    = string
  default = ""
}

variable "target_group_arn_suffix" {
  type    = string
  default = ""
}

variable "neptune_enabled" {
  type = bool
}

variable "neptune_cluster_identifier" {
  description = "Empty when Neptune is disabled."
  type        = string
  default     = ""
}
