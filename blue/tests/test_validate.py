from conftest import make_fixture, make_optout, make_vultr, make_vultr_optout
from package_posthog_blue import validate


def test_vultr_fixtures_are_valid():
    assert validate.state_errors(make_vultr()) == []
    assert validate.state_errors(make_vultr_optout()) == []


# --- the registry (Compute Provider Standard §2)


# --- the network contract (§5)


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


def test_reports_all_errors():
    errors = validate.state_errors(
        make_fixture(**{"posthog-host": "bad", "posthog-image": "floating",
                        "posthog-backup-retention-days": -1,
                        "provider-dns": "other",
                        "digitalocean-vpc-uuid": "forbidden"}))
    assert len(errors) >= 4
    for part in ["host", "image", "retention", "provider-dns"]:
        assert any(part in e for e in errors)


def test_profile_overlay_is_refused():
    assert validate.env_errors({"COLORS_PAR_PROFILE": "other"})
    assert not validate.env_errors({})
