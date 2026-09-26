# Runs inside the new system. Rebuild the initramfs now that casper and its
# settings are in place; it is what finds and mounts the live filesystem.
set -euo pipefail

update-initramfs -u -k all
