from conftest import make_fixture, make_optout, make_vultr, make_vultr_optout
from package_posthog_blue import validate


def test_vultr_fixtures_are_valid():
    assert validate.state_errors(make_vultr()) == []
    assert validate.state_errors(make_vultr_optout()) == []


# --- the registry (Compute Provider Standard §2)


def test_advertised_providers():
    assert sorted(validate.compute_providers) == ["digitalocean", "vultr"]
    assert validate.default_compute_provider == "digitalocean"


def test_unsupported_provider_is_named_with_the_alternatives():
    assert ":provider-compute must be one of digitalocean, vultr" in \
        validate.state_errors(make_fixture(**{"provider-compute": "hcloud"}))


def test_each_provider_requires_its_own_keys_and_ignores_the_others():
    errors = validate.state_errors(make_fixture(**{"provider-compute": "vultr"}))
    for k in ["vultr-region", "vultr-plan", "vultr-os-id", "vultr-ssh-sources", "vultr-http-sources"]:
        assert f":{k} is required" in errors
    assert not any("digitalocean" in e for e in errors)
    errors = validate.state_errors(make_vultr(**{"provider-compute": "digitalocean"}))
    for k in ["digitalocean-region", "digitalocean-size", "digitalocean-image",
              "digitalocean-ssh-sources", "digitalocean-http-sources"]:
        assert f":{k} is required" in errors
    assert not any("vultr" in e for e in errors)
    # Unselected-provider keys are accepted so one colors.yml stays portable.
    assert validate.state_errors(make_fixture(**{"vultr-region": "ams", "vultr-ssh-keys": "x"})) == []


def test_per_provider_checks_run_only_for_the_selected_provider():
    assert any("vultr-os-id" in e for e in validate.state_errors(make_vultr(**{"vultr-os-id": "2284"})))
    assert validate.state_errors(make_fixture(**{"vultr-os-id": "2284"})) == []
    assert any("vpc-uuid" in e for e in validate.state_errors(make_fixture(**{"digitalocean-vpc-uuid": "x"})))
    assert validate.state_errors(make_vultr(**{"digitalocean-vpc-uuid": "x"})) == []


def test_secrets_and_tofu_env_follow_the_selected_provider():
    do_errors = "\n".join(validate.secret_errors(make_fixture()))
    vultr_errors = "\n".join(validate.secret_errors(make_vultr()))
    assert "COLORS_PAR_DO_TOKEN" in do_errors and "VULTR_API_KEY" not in do_errors
    assert "COLORS_PAR_VULTR_API_KEY" in vultr_errors and "DO_TOKEN" not in vultr_errors
    assert validate.tofu_env(make_fixture(), "provider-compute") == {"do-token": "DIGITALOCEAN_TOKEN"}
    assert validate.tofu_env(make_vultr(), "provider-compute") == {"vultr-api-key": "VULTR_API_KEY"}
    assert validate.tofu_env(make_fixture(**{"provider-compute": "hcloud"}), "provider-compute") == {}


def test_compute_key_is_provider_scoped_and_keygen_follows_the_selected_provider_key():
    assert validate.compute_key(make_fixture(), "ssh-sources") == "digitalocean-ssh-sources"
    assert validate.compute_key(make_vultr(), "name") == "vultr-name"
    assert validate.keygen(make_vultr()) is True
    assert validate.keygen(make_vultr_optout()) is False
    # A DigitalOcean key id in a Vultr deployment is an unselected key: ignored.
    assert validate.keygen(make_vultr(**{"digitalocean-ssh-keys": "58495393"})) is True
    assert validate.compute_name(make_vultr()) == "posthog-vultr-fixture"
    assert validate.compute_name(make_vultr_optout()) == "posthog-vultr-optout"
    assert validate.compute_name(make_vultr(**{"digitalocean-name": "other"})) == "posthog-vultr-fixture"
    assert any("vultr-name" in e for e in validate.state_errors(make_vultr(**{"vultr-name": "not valid!"})))
    # Provider-specific rules: DigitalOcean names are hostname-like, Vultr
    # labels are only a console string.
    assert any("digitalocean-name" in e for e in
               validate.state_errors(make_fixture(**{"digitalocean-name": "invalid_name"})))
    assert any("digitalocean-name" in e for e in
               validate.state_errors(make_fixture(**{"digitalocean-name": "Upper-Case"})))
    assert validate.state_errors(make_vultr(**{"vultr-name": "invalid_name"})) == []
    assert validate.state_errors(make_vultr(**{"vultr-name": "Upper_Case.1"})) == []


# --- the network contract (§5)


def test_cidr_syntax():
    for ok in ["0.0.0.0/0", "10.0.0.0/8", "203.0.113.7/32", "::/0", "2001:db8::/32",
               "fe80::1/128", "::ffff:192.0.2.10/96", "2001:db8:0:0:0:0:0:1/64"]:
        assert validate.cidr(ok), ok
    for bad in ["", "10.0.0.0", "10.0.0.0/33", "256.0.0.1/8", "10.0.0/8", "::/129",
                "2001:db8::/-1", "2001:db8:::1/64", "1:2:3:4:5:6:7:8:9/64", "g::1/64",
                "10.0.0.0/8/8", "example.com/24"]:
        assert not validate.cidr(bad), bad


def test_ssh_sources_must_reach_someone_and_malformed_entries_are_refused():
    for make in (make_fixture, make_vultr):
        opts = make()
        ssh_key = validate.compute_key(opts, "ssh-sources")
        http_key = validate.compute_key(opts, "http-sources")
        assert any("at least one CIDR" in e for e in validate.state_errors({**opts, ssh_key: []}))
        assert any("not an IPv4 or IPv6 CIDR: 10.0.0.0" in e
                   for e in validate.state_errors({**opts, ssh_key: ["10.0.0.0"]}))
        assert any("not an IPv4 or IPv6 CIDR: nope" in e
                   for e in validate.state_errors({**opts, http_key: ["0.0.0.0/0", "nope"]}))
        # An empty http list means no public HTTP and is allowed.
        assert validate.state_errors({**opts, http_key: []}) == []
        # Overlay strings are split the way the template reads them.
        assert validate.state_errors({**opts, ssh_key: "10.0.0.0/8, 192.0.2.0/24"}) == []


# --- provider switching is a rebuild (§4)


def test_provider_state_refuses_a_switch_accepts_the_recorded_provider_and_holds_legacy_to_default():
    switch = "state holds a vultr machine; set provider-compute back to vultr and delete first"
    back = "state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"
    assert validate.provider_state_errors(make_fixture(), {"provider": "vultr", "ip": "203.0.113.7"}) == [switch]
    assert validate.provider_state_errors(make_vultr(), {"provider": "digitalocean", "ip": "203.0.113.7"}) == [back]
    assert validate.provider_state_errors(make_fixture(), {"provider": "digitalocean", "ip": "203.0.113.7"}) == []
    assert validate.provider_state_errors(make_vultr(), {"provider": "vultr", "ip": "203.0.113.7"}) == []
    assert validate.provider_state_errors(make_fixture(), None) == []
    assert validate.provider_state_errors(make_vultr(), None) == []
    # Every deployment created before adoption ran the package default.
    assert validate.provider_state_errors(make_fixture(), {"ip": "203.0.113.7"}) == []
    assert validate.provider_state_errors(make_vultr(), {"ip": "203.0.113.7"}) == [back]


def test_fixture_is_valid():
    assert validate.state_errors(make_fixture()) == []


def test_optout_fixture_is_valid():
    assert validate.state_errors(make_optout()) == []


def test_machine_key_is_not_required():
    # The standard makes absence meaningful: requiring digitalocean-ssh-keys
    # would make every conforming keygen deployment invalid.
    assert not any("digitalocean-ssh-keys" in e for e in validate.state_errors(make_fixture()))


def test_absent_machine_key_selects_keygen():
    assert validate.keygen(make_fixture()) is True
    assert validate.keygen(make_optout()) is False


def test_compute_name_defaults_to_the_profile_and_honours_the_override():
    assert validate.compute_name(make_fixture()) == "posthog-fixture"
    assert validate.compute_name(make_fixture(**{"digitalocean-name": ""})) == "posthog-fixture"
    assert validate.compute_name(make_fixture(**{"digitalocean-name": "REPLACE_ME"})) == "posthog-fixture"
    assert validate.compute_name(make_optout()) == "posthog-optout"
    assert validate.compute_name(make_fixture(**{"digitalocean-name": " analytics-1 "})) == "analytics-1"


def test_compute_name_is_not_required_but_is_validated():
    assert not any("digitalocean-name" in e for e in validate.state_errors(make_fixture()))
    assert any("digitalocean-name" in e for e in
               validate.state_errors(make_fixture(**{"digitalocean-name": "not valid!"})))


def test_reports_all_errors():
    errors = validate.state_errors(
        make_fixture(**{"posthog-host": "bad", "posthog-image": "floating",
                        "posthog-backup-retention-days": -1,
                        "provider-dns": "other",
                        "digitalocean-vpc-uuid": "forbidden"}))
    assert len(errors) >= 5
    for part in ["host", "image", "retention", "provider-dns", "vpc-uuid"]:
        assert any(part in e for e in errors)


def test_forbids_vpc_configuration():
    errors = validate.state_errors(make_fixture(**{"digitalocean-vpc-cidr": "10.0.0.0/16"}))
    assert any("must be absent" in e for e in errors)


def test_profile_overlay_is_refused():
    assert validate.env_errors({"COLORS_PAR_PROFILE": "other"})
    assert not validate.env_errors({})


def test_names_all_package_secrets():
    errors = "\n".join(validate.secret_errors(make_fixture()))
    for name in ["COLORS_PAR_DO_TOKEN", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
                 "COLORS_PAR_R2_ACCESS_KEY_ID", "COLORS_PAR_R2_SECRET_ACCESS_KEY",
                 "COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID",
                 "COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY",
                 "COLORS_PAR_POSTHOG_SECRET_KEY",
                 "COLORS_PAR_POSTHOG_POSTGRES_PASSWORD",
                 "COLORS_PAR_POSTHOG_OIDC_RSA_PRIVATE_KEY",
                 "COLORS_PAR_POSTHOG_ENCRYPTION_SALT_KEYS"]:
        assert name in errors
