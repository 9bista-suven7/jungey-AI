#!/usr/bin/env bash
# Try Jungey OS in a virtual machine before putting it on real hardware.
#
#   os/test/run-vm.sh                    boot the ISO (BIOS firmware)
#   os/test/run-vm.sh --uefi             boot the ISO with UEFI firmware
#   os/test/run-vm.sh --disk             ...with a 25 GB virtual disk to install onto
#   os/test/run-vm.sh --uefi --no-iso    boot what was installed on that disk
#
# The ISO defaults to the newest one in os/out; pass a path to use another.
# Needs qemu-system-x86 (and ovmf for --uefi). Uses KVM when it is available.
set -euo pipefail

OS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
iso="" uefi=0 disk=0 use_iso=1
for arg in "$@"; do
    case $arg in
        --uefi) uefi=1 ;;
        --disk) disk=1 ;;
        --no-iso) use_iso=0; disk=1 ;;
        -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
        *) iso=$arg ;;
    esac
done
if [ $use_iso = 1 ] && [ -z "$iso" ]; then
    iso=$(ls -t "$OS_DIR"/out/*.iso 2>/dev/null | head -1 || true)
    [ -n "$iso" ] || { echo "no ISO in $OS_DIR/out - build one with os/build.sh" >&2; exit 1; }
fi

state=${XDG_CACHE_HOME:-$HOME/.cache}/jungey-os-vm
mkdir -p "$state"
args=(-m 4096 -smp "$(nproc)" -vga virtio -usb -device usb-tablet
      -netdev user,id=net0 -device virtio-net-pci,netdev=net0
      -audiodev none,id=snd0 -device intel-hda -device hda-duplex,audiodev=snd0)
if [ -w /dev/kvm ]; then
    args+=(-enable-kvm -cpu host)
else
    echo "note: no KVM here, so the VM is emulated and slow (minutes to reach the desktop)" >&2
    args+=(-cpu max)
fi
if [ $uefi = 1 ]; then
    [ -f "$state/OVMF_VARS.fd" ] || cp /usr/share/OVMF/OVMF_VARS_4M.fd "$state/OVMF_VARS.fd"
    args+=(-machine q35
           -drive if=pflash,format=raw,readonly=on,file=/usr/share/OVMF/OVMF_CODE_4M.fd
           -drive if=pflash,format=raw,file="$state/OVMF_VARS.fd")
fi
if [ $disk = 1 ]; then
    [ -f "$state/disk.qcow2" ] || qemu-img create -f qcow2 "$state/disk.qcow2" 25G >/dev/null
    args+=(-drive file="$state/disk.qcow2",if=virtio,format=qcow2)
fi
if [ $use_iso = 1 ]; then
    args+=(-drive file="$iso",media=cdrom,readonly=on -boot order=dc)
fi
exec qemu-system-x86_64 "${args[@]}"
