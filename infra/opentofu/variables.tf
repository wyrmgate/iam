variable "region" {
  description = "OCI region in which the foundation is created."
  type        = string
}

variable "compartment_ocid" {
  description = "OCI compartment OCID for all resources in this foundation."
  type        = string
}

variable "availability_domain" {
  description = "Availability domain for the compute instance."
  type        = string
}

variable "image_ocid" {
  description = "Region-specific OCI image OCID for the host."
  type        = string
}

variable "ssh_public_key" {
  description = "OpenSSH public key authorized for the host. Never provide a private key."
  type        = string
  sensitive   = true
}

variable "admin_cidr" {
  description = "CIDR allowed to reach SSH on port 22. Use a narrow administrator/network CIDR."
  type        = string

  validation {
    condition     = can(cidrhost(var.admin_cidr, 0)) && var.admin_cidr != "0.0.0.0/0" && var.admin_cidr != "::/0"
    error_message = "admin_cidr must be a valid CIDR and must not expose SSH to the entire internet."
  }
}

variable "name_prefix" {
  description = "Environment-specific prefix used for OCI resource display names."
  type        = string
  default     = "wyrmgate-iam-demo"

  validation {
    condition     = length(trimspace(var.name_prefix)) > 0
    error_message = "name_prefix must not be empty."
  }
}

variable "vcn_dns_label" {
  description = "Environment-specific OCI DNS label for the VCN. Must start with a lowercase letter and contain at most 15 lowercase alphanumeric characters."
  type        = string
  default     = "iamdemo"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{0,14}$", var.vcn_dns_label))
    error_message = "vcn_dns_label must start with a lowercase letter and contain at most 15 lowercase alphanumeric characters."
  }
}

variable "hostname_label" {
  description = "Environment-specific hostname label for the compute VNIC."
  type        = string
  default     = "iamdemo"

  validation {
    condition     = length(var.hostname_label) >= 1 && length(var.hostname_label) <= 63 && can(regex("^[a-z]([a-z0-9-]*[a-z0-9])?$", var.hostname_label))
    error_message = "hostname_label must be 1-63 lowercase letters, digits, or hyphens, start with a letter, and end with a letter or digit."
  }
}

variable "vcn_cidr" {
  description = "CIDR for the VCN."
  type        = string
  default     = "10.20.0.0/16"
}

variable "subnet_cidr" {
  description = "CIDR for the public subnet."
  type        = string
  default     = "10.20.10.0/24"
}

variable "instance_shape" {
  description = "OCI flexible compute shape. The default targets the Arm Always Free eligible shape where available."
  type        = string
  default     = "VM.Standard.A1.Flex"
}

variable "instance_ocpus" {
  description = "OCPUs allocated to the flexible compute shape."
  type        = number
  default     = 1
}

variable "instance_memory_gb" {
  description = "Memory in GB allocated to the flexible compute shape."
  type        = number
  default     = 6
}
