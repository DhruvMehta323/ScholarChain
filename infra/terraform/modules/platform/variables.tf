variable "name" {
  type = string
}

variable "service_names" {
  description = "One ECR repository per deployable service."
  type        = list(string)
}

variable "ecr_keep_images" {
  type    = number
  default = 5
}

variable "queue_name" {
  type = string
}

variable "dlq_name" {
  type = string
}

variable "embedding_visibility_timeout" {
  type    = number
  default = 300
}

variable "max_receive_count" {
  description = "Receives before a message moves to the dead-letter queue."
  type        = number
  default     = 3
}

variable "secret_names" {
  description = "Internal key to Secrets Manager name."
  type        = map(string)
}

variable "secret_recovery_window_days" {
  type    = number
  default = 7
}

variable "ecr_force_delete" {
  description = "Delete repositories that still contain images. Default false; the human flips it for cleanup."
  type        = bool
  default     = false
}
