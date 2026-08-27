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

resource "digitalocean_droplet" "posthog" {
  name     = "<{ digitalocean-name }>"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = data.digitalocean_vpc.default.id
  ssh_keys = ["<{ digitalocean-ssh-keys }>"]
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_firewall" "posthog" {
  name        = "<{ digitalocean-name }>-firewall"
  droplet_ids = [digitalocean_droplet.posthog.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = <{ ssh-sources-hcl|safe }>
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "80"
    source_addresses = <{ http-sources-hcl|safe }>
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "443"
    source_addresses = <{ http-sources-hcl|safe }>
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
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

output "params" {
  value = { ip = digitalocean_droplet.posthog.ipv4_address, user = "root", sudoer = "root", name = "<{ profile }>" }
}
