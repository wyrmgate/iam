variable "region" {
  description = "OCI region in which the demo foundation is created."
  type        = string
}

variable "compartment_ocid" {
  description = "OCI compartment OCID for all demo resources."
  type        = string
}

variable "availability_domain" {
  description = "Availability domain for the demo compute instance."
  type        = string
}

variable "image_ocid" {
  description = "Region-specific OCI image OCID for the demo host."
  type        = string
}

variable "ssh_public_key" {
  description = "OpenSSH public key authorized for the demo host. Never provide a private key."
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
  description = "Prefix used for OCI resource display names."
  type        = string
  default     = "wyrmgate-iam-demo"
}

variable "vcn_cidr" {
  description = "CIDR for the demo VCN."
  type        = string
  default     = "10.20.0.0/16"
}

variable "subnet_cidr" {
  description = "CIDR for the public demo subnet."
  type        = string
  default     = "10.20.10.0/24"
}

variable "instance_shape" {
  description = "OCI flexible compute shape. The default targets the Arm Always Free eligible shape where available."
  type        = string
  default     = "VM.Standard.A1.Flex"
}

variable "instance_ocpus" {
  description = "OCPUs allocated to the flexible demo shape."
  type        = number
  default     = 1
}

variable "instance_memory_gb" {
  description = "Memory in GB allocated to the flexible demo shape."
  type        = number
  default     = 6
}
