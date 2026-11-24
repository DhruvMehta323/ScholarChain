variable "name" {
  type = string
}

variable "state_machine_name" {
  type = string
}

variable "sfn_role_arn" {
  type = string
}

variable "log_group_arn" {
  type = string
}

variable "release_label" {
  description = "EMR release label (for example emr-spark-8.1.0)."
  type        = string
  validation {
    condition     = contains(["emr-spark-8.1.0", "emr-7.14.0"], var.release_label)
    error_message = "release_label must be emr-spark-8.1.0 (default) or emr-7.14.0 (MapReduce fallback)."
  }
  validation {
    condition     = can(regex("^emr-[a-z0-9.-]+$", var.release_label))
    error_message = "release_label must match ^emr-[a-z0-9.-]+$ (for example emr-spark-8.1.0)."
  }
}

variable "applications" {
  description = "EMR applications requested at cluster creation. emr-spark-8.1.0 lists Spark (not Hadoop) as an application; Hadoop/YARN/MapReduce come as components. The 7.14.0 fallback needs the Hadoop application."
  type        = list(string)
  default     = ["Spark"]
  validation {
    condition     = length(var.applications) > 0 && alltrue([for a in var.applications : contains(["Hadoop", "Spark"], a)])
    error_message = "applications must be a non-empty list drawn from Hadoop and Spark."
  }
  validation {
    condition     = var.release_label != "emr-7.14.0" || contains(var.applications, "Hadoop")
    error_message = "emr-7.14.0 must be used with the Hadoop application (it is the MapReduce fallback)."
  }
}

variable "instance_type" {
  description = "One instance type for master and core. Larger classes need human approval with a cost estimate."
  type        = string
  default     = "m5.xlarge"
}

variable "core_instance_count" {
  type    = number
  default = 1
  validation {
    condition     = var.core_instance_count >= 1 && var.core_instance_count <= 2
    error_message = "core_instance_count must be 1 or 2 (1 primary + at most 2 = 3 nodes, guard limit)."
  }
}

variable "max_capacity_units" {
  description = "Managed scaling maximum for core + task nodes only (AWS: the primary node is not counted). With the single primary the cluster never exceeds 1 + this value = 3 nodes."
  type        = number
  default     = 2
  validation {
    condition     = var.max_capacity_units >= 1 && var.max_capacity_units <= 2 && var.max_capacity_units >= var.core_instance_count
    error_message = "max_capacity_units must be 1 or 2 and at least core_instance_count (total nodes <= 3 with the primary)."
  }
}

variable "idle_timeout_seconds" {
  description = "EMR auto-termination idle timeout in seconds (default 900). Idle means no active YARN application and no pending step, so it never fires during a running step."
  type        = number
  default     = 900
  validation {
    condition     = var.idle_timeout_seconds >= 60 && var.idle_timeout_seconds <= 3600
    error_message = "idle_timeout_seconds must be between 60 and 3600 for this course environment."
  }
}

variable "execution_timeout_seconds" {
  description = "Hard bound on one pipeline execution."
  type        = number
  default     = 14400
}

variable "service_role_name" {
  type = string
}

variable "instance_profile_name" {
  type = string
}

variable "logs_bucket_name" {
  type = string
}

variable "subnet_id" {
  description = "A public subnet (NAT-free design)."
  type        = string
}

variable "security_group_id" {
  type = string
}

variable "tags" {
  description = "Tags applied to the EMR cluster itself (clusters launched by the state machine are not covered by provider default_tags)."
  type        = map(string)
}

variable "step_timeout_seconds" {
  description = "Bound on each EMR step."
  type        = number
  default     = 10800
}
