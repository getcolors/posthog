from conftest import make_fixture
from package_posthog_blue import validate


def test_fixture_is_valid():
    assert validate.state_errors(make_fixture()) == []


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
