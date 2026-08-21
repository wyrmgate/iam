output "instance_id" {
  description = "OCID of the demo compute instance."
  value       = oci_core_instance.demo.id
}

output "public_ip" {
  description = "Public IPv4 address assigned to the demo host."
  value       = oci_core_instance.demo.public_ip
}

output "subnet_id" {
  description = "OCID of the public demo subnet."
  value       = oci_core_subnet.public.id
}

output "vcn_id" {
  description = "OCID of the demo VCN."
  value       = oci_core_vcn.demo.id
}
