from conftest import fixture, optout
from package_posthog_blue import ssh

def test_managed_build_identity_is_deterministic():
    opts = ssh.with_machine_key({**fixture(), 'blue/event': 'build'})
    assert opts['ssh-private-key-path'] == '/home/build-placeholder/.ssh/posthog-fixture'
    assert ssh.identity_args(opts) == ['-i', opts['ssh-private-key-path'], '-o', 'IdentitiesOnly=yes']

def test_external_identity_is_preserved():
    opts = {**optout(), 'blue/event': 'build'}
    assert ssh.with_machine_key(opts) == opts
    assert ssh.identity_args(opts)[1] == opts['ssh-private-key-path']

def test_live_identity_is_never_generated_by_the_application():
    opts = fixture()
    assert ssh.with_machine_key(opts) == opts
