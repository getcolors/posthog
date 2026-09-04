from conftest import make_fixture, make_optout
from package_posthog_blue import validate


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
