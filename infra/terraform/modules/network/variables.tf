variable "name" {
  description = "Name prefix for network resources."
  type        = string
}

variable "vpc_cidr" {
  type = string
}

variable "azs" {
  description = "Exactly two Availability Zones."
  type        = list(string)
  validation {
    condition     = length(var.azs) == 2
    error_message = "Exactly two Availability Zones are required."
  }
}

variable "alb_ingress_cidrs" {
  description = "CIDR blocks allowed to reach the load balancer listener(s). The only broad CIDR in the design."
  type        = list(string)
}

variable "alb_https_enabled" {
  description = "Open port 443 on the load balancer security group."
  type        = bool
  default     = false
}

variable "service_port" {
  description = "Container port of query-api and retrieval."
  type        = number
  default     = 8080
}

variable "neptune_port" {
  type    = number
  default = 8182
}
