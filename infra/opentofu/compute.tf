resource "oci_core_instance" "demo" {
  availability_domain = var.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "${var.name_prefix}-host"
  shape               = var.instance_shape

  shape_config {
    ocpus         = var.instance_ocpus
    memory_in_gbs = var.instance_memory_gb
  }

  create_vnic_details {
    assign_public_ip = true
    display_name     = "${var.name_prefix}-vnic"
    hostname_label   = var.hostname_label
    subnet_id        = oci_core_subnet.public.id
  }

  source_details {
    source_id   = var.image_ocid
    source_type = "image"
  }

  metadata = {
    ssh_authorized_keys = trimspace(var.ssh_public_key)
  }

  preserve_boot_volume = false
}
