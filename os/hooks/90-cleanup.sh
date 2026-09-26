# Runs inside the new system. Leave nothing machine-specific or disposable in
# the image.
set -euo pipefail

apt-get -y autoremove --purge
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin
rm -f /var/cache/debconf/*-old /var/lib/dpkg/*-old

# Every installed machine must generate its own identity on first boot.
: > /etc/machine-id
rm -f /var/lib/dbus/machine-id
ln -s /etc/machine-id /var/lib/dbus/machine-id
rm -f /etc/ssh/ssh_host_*

# Ubuntu's normal resolver setup (the build pointed it elsewhere).
ln -sf ../run/systemd/resolve/stub-resolv.conf /etc/resolv.conf

find /var/log -type f -exec truncate -s 0 {} +
rm -rf /tmp/* /var/tmp/* /root/.bash_history /root/.cache
