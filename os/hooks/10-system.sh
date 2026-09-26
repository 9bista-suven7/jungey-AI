# Runs inside the new system. Locale, identity and the live session's account.
set -euo pipefail

sed -i 's/^# *\(en_US.UTF-8 UTF-8\)/\1/' /etc/locale.gen
locale-gen
update-locale LANG=en_US.UTF-8

# Name the system Jungey OS while keeping ID=ubuntu: apt tooling, PPAs and
# unattended security upgrades all key off the ID, and they must keep working.
# The diversion keeps base-files updates from putting Ubuntu's file back.
if ! dpkg-divert --list /usr/lib/os-release | grep -q .; then
    dpkg-divert --local --rename --divert /usr/lib/os-release.ubuntu --add /usr/lib/os-release
fi
cat > /usr/lib/os-release <<EOF
PRETTY_NAME="$OS_NAME $OS_VERSION"
NAME="$OS_NAME"
VERSION_ID="$SUITE_VERSION"
VERSION="$OS_VERSION (Ubuntu $SUITE_VERSION LTS base)"
VERSION_CODENAME=$SUITE
ID=ubuntu
ID_LIKE=debian
VARIANT="$OS_NAME"
VARIANT_ID=$OS_ID
HOME_URL="https://github.com/9bista-suven7/jungey-AI"
SUPPORT_URL="https://github.com/9bista-suven7/jungey-AI/issues"
BUG_REPORT_URL="https://github.com/9bista-suven7/jungey-AI/issues"
UBUNTU_CODENAME=$SUITE
LOGO=jungey
EOF

echo "$HOSTNAME_LIVE" > /etc/hostname
cat > /etc/hosts <<EOF
127.0.0.1	localhost
127.0.1.1	$HOSTNAME_LIVE

::1	localhost ip6-localhost ip6-loopback
ff02::1	ip6-allnodes
ff02::2	ip6-allrouters
EOF

# casper reads this at boot to create the passwordless live user.
cat > /etc/casper.conf <<EOF
export USERNAME="$LIVE_USER"
export USERFULLNAME="Live session user"
export HOST="$HOSTNAME_LIVE"
export BUILD_SYSTEM="Ubuntu"
export FLAVOUR="Jungey"
EOF

# NetworkManager runs the network (netplan hands it everything), so networkd
# would only hold up boot waiting for links it does not manage.
chmod 600 /etc/netplan/*.yaml
systemctl disable systemd-networkd.service systemd-networkd.socket \
    systemd-networkd-wait-online.service 2>/dev/null || true
systemctl mask systemd-networkd-wait-online.service
