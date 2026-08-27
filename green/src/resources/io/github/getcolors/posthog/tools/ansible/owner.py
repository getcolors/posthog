# Provision the owner account.
#
# A fresh instance has no user, and PostHog's hosted realm only lets the *first*
# user create an organization. Once anything creates one -- including the
# acceptance step asking for a project -- the signup page shows an invite wall
# and nobody can get in at all. So the deployment owns this account rather than
# leaving it to a signup that may already be impossible.
#
# Idempotent: bootstrap on an empty instance, join an existing organization, or
# rotate the password of an account that is already there.
import os

from posthog.models import Organization, User

email = os.environ["PH_EMAIL"]
password = os.environ["PH_PASSWORD"]

user = User.objects.filter(email=email).first()
organization = Organization.objects.first()

if user is not None:
    user.set_password(password)
    user.is_staff = True
    user.save()
    print("OWNER=rotated")
elif organization is not None:
    User.objects.create_and_join(
        organization=organization, email=email, password=password, first_name="Colors"
    )
    User.objects.filter(email=email).update(is_staff=True)
    print("OWNER=joined")
else:
    User.objects.bootstrap(
        organization_name="Colors",
        email=email,
        password=password,
        first_name="Colors",
        is_staff=True,
    )
    print("OWNER=bootstrapped")
