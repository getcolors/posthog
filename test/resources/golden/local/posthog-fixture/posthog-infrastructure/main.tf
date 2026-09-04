terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "~> 2.0" }
  }
}
provider "digitalocean" {}

# Discover the configured region's account default at plan/apply time. The UUID
# is deliberately neither configured nor persisted in colors.yml.
data "digitalocean_vpc" "default" {
  name = "default-ams3"
}

# The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable. Never reference a
# literal key id here in keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "posthog-fixture"
  public_key = trimspace(file("/home/build-placeholder/.ssh/posthog-fixture.pub"))
}

resource "digitalocean_droplet" "posthog" {
  name     = "posthog-fixture"
  region   = "ams3"
  size     = "s-4vcpu-8gb"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = data.digitalocean_vpc.default.id
  # SSH keys are ids already in the account, and ForceNew: changing the key set
  # destroys and recreates the droplet instead of re-authorizing it. Rotation
  # is a rebuild, never an edit on a machine whose disk you intend to keep.
  ssh_keys = [digitalocean_ssh_key.machine.id]
  # Wait for ssh before starting Ansible.
  connection {
    type        = "ssh"
    user        = "root"
    host        = self.ipv4_address
    private_key = file("/home/build-placeholder/.ssh/posthog-fixture")
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle { prevent_destroy = true }
}

resource "digitalocean_firewall" "posthog" {
  name        = "posthog-fixture-firewall"
  droplet_ids = [digitalocean_droplet.posthog.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "80"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "443"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  lifecycle { prevent_destroy = true }
}

output "params" {
  value = {
    provider   = "digitalocean"
    ip         = digitalocean_droplet.posthog.ipv4_address
    user       = "root"
    sudoer     = "root"
    name       = "posthog-fixture"
    ssh_key_id = digitalocean_ssh_key.machine.id
  }
}
