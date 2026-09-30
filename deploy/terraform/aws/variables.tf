variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "dev, staging or prod: prod turns on Multi-AZ, deletion protection and one NAT per AZ"
  type        = string
  default     = "dev"
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod"
  }
}

variable "db_instance_class" {
  description = "RDS instance class for Postgres"
  type        = string
  default     = "db.r6g.large"
}
