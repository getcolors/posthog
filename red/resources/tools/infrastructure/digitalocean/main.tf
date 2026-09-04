terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "~> 2.0" }
  }
}
provider "digitalocean" {}

# Discover the configured region's account default at plan/apply time. The UUID
# is deliberately neither configured nor persisted in colors.yml.
data "digitalocean_vpc" "default" {
  name = "default-<{ digitalocean-region }>"
}

<% if ssh-keygen %># The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable. Never reference a
# literal key id here in keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "<{ profile }>"
  public_key = trimspace(file("<{ ssh-public-key-path }>"))
}

<% endif %>resource "digitalocean_droplet" "posthog" {
  name     = "<{ compute-name }>"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = data.digitalocean_vpc.default.id
<% if ssh-keygen %>  # SSH keys are ids already in the account, and ForceNew: changing the key set
  # destroys and recreates the droplet instead of re-authorizing it. Rotation
  # is a rebuild, never an edit on a machine whose disk you intend to keep.
  ssh_keys = [digitalocean_ssh_key.machine.id]
  # Wait for ssh before starting Ansible.
  connection {
    type        = "ssh"
    user        = "root"
    host        = self.ipv4_address
    private_key = file("<{ ssh-private-key-path }>")
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
<% else %>  ssh_keys = ["<{ digitalocean-ssh-keys }>"]
<% endif %>  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_firewall" "posthog" {
  name        = "<{ compute-name }>-firewall"
  droplet_ids = [digitalocean_droplet.posthog.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = <{ ssh-sources-hcl|safe }>
  }
<% if http-sources? %>  inbound_rule {
    protocol         = "tcp"
    port_range       = "80"
    source_addresses = <{ http-sources-hcl|safe }>
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "443"
    source_addresses = <{ http-sources-hcl|safe }>
  }
<% endif %>  outbound_rule {
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
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

output "params" {
<% if ssh-keygen %>  value = {
    provider   = "digitalocean"
    ip         = digitalocean_droplet.posthog.ipv4_address
    user       = "root"
    sudoer     = "root"
    name       = "<{ compute-name }>"
    ssh_key_id = digitalocean_ssh_key.machine.id
  }
<% else %>  value = { provider = "digitalocean", ip = digitalocean_droplet.posthog.ipv4_address, user = "root", sudoer = "root", name = "<{ compute-name }>" }
<% endif %>}
