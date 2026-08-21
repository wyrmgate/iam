terraform {
  required_version = "= 1.12.5"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "= 8.25.0"
    }
  }
}
