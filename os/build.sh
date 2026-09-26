#!/usr/bin/env bash
# Build the Jungey OS live and installer ISO.
#
#   sudo ./os/build.sh
#
# Produces os/out/jungey-os-<version>-amd64.iso, which boots on BIOS and UEFI
# machines (Secure Boot included) from a USB stick or DVD, runs a live desktop,
# and installs itself to disk with a graphical installer.
#
# Runs on Ubuntu 24.04 as root. Host packages it needs:
#   debootstrap squashfs-tools xorriso mtools dosfstools librsvg2-bin rsync
# plus JDK 21 and Maven to build Jungey itself, unless JUNGEY_JAR points at a
# jar that is already built.
#
# Environment knobs (see config/os.conf for the rest):
#   WORK=dir        scratch space, about 12 GB (default os/work)
#   OUT=dir         where the ISO lands (default os/out)
#   CLEAN=1         start from nothing instead of reusing the bootstrapped system
#   JUNGEY_JAR=f    use this jar instead of running Maven
set -euo pipefail

OS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(dirname "$OS_DIR")
# shellcheck source=config/os.conf
. "$OS_DIR/config/os.conf"

WORK=${WORK:-$OS_DIR/work}
OUT=${OUT:-$OS_DIR/out}
ROOTFS=$WORK/rootfs
ISO=$WORK/iso
DEBS=$WORK/debs

# One timestamp names the build everywhere: the ISO's volume UUID, which GRUB
# searches for, is derived from it, so a stray second USB stick cannot hijack
# the boot.
BUILD_EPOCH=${SOURCE_DATE_EPOCH:-$(date +%s)}
BUILD_DATE=$(date -u -d "@$BUILD_EPOCH" +%Y%m%d)
ISO_STAMP=$(date -u -d "@$BUILD_EPOCH" +%Y%m%d%H%M%S00)
ISO_UUID=$(date -u -d "@$BUILD_EPOCH" +%Y-%m-%d-%H-%M-%S-00)
ISO_NAME="${OS_ID}-os-${OS_VERSION}-${ARCH}.iso"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Strip comments and blank lines from a package list.
read_list() { sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$1"; }

check_host() {
    [ "$(id -u)" -eq 0 ] || die "run as root: sudo $0"
    local missing=()
    for tool in debootstrap mksquashfs xorriso mcopy mkfs.vfat rsvg-convert rsync; do
        command -v "$tool" >/dev/null || missing+=("$tool")
    done
    [ ${#missing[@]} -eq 0 ] || die "missing host tools: ${missing[*]}
  sudo apt-get install debootstrap squashfs-tools xorriso mtools dosfstools librsvg2-bin rsync"
}

# --- chroot plumbing ---------------------------------------------------------

mount_chroot() {
    mountpoint -q "$ROOTFS/proc" && return
    # Keep package scripts from starting daemons inside the build.
    printf '#!/bin/sh\nexit 101\n' > "$ROOTFS/usr/sbin/policy-rc.d"
    chmod 755 "$ROOTFS/usr/sbin/policy-rc.d"
    mount --bind /dev "$ROOTFS/dev"
    mount --bind /dev/pts "$ROOTFS/dev/pts"
    mount -t proc proc "$ROOTFS/proc"
    mount -t sysfs sysfs "$ROOTFS/sys"
    mount -t tmpfs tmpfs "$ROOTFS/run"
    # /etc/resolv.conf becomes a link into /run once systemd-resolved is in;
    # give it something to point at so apt keeps resolving names.
    mkdir -p "$ROOTFS/run/systemd/resolve"
    cp -L /etc/resolv.conf "$ROOTFS/run/systemd/resolve/stub-resolv.conf"
    cp -L /etc/resolv.conf "$ROOTFS/run/systemd/resolve/resolv.conf"
}

umount_chroot() {
    local m
    for m in run sys proc dev/pts dev; do
        if mountpoint -q "$ROOTFS/$m" 2>/dev/null; then
            umount "$ROOTFS/$m" 2>/dev/null || umount -l "$ROOTFS/$m"
        fi
    done
    rm -f "$ROOTFS/usr/sbin/policy-rc.d"
}

# make_squashfs parks the initramfs outside the tree while it compresses; put
# it back if the build stops there, or the next run's hooks cannot update it.
restore_initrd() {
    if [ -d "$WORK/held-initrd" ] && [ -n "$(ls -A "$WORK/held-initrd")" ]; then
        mv "$WORK"/held-initrd/* "$ROOTFS/boot/"
    fi
}
trap 'umount_chroot; restore_initrd' EXIT

in_chroot() {
    chroot "$ROOTFS" /usr/bin/env -i \
        HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 \
        DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
        ${http_proxy:+http_proxy=$http_proxy} ${https_proxy:+https_proxy=$https_proxy} \
        OS_NAME="$OS_NAME" OS_ID="$OS_ID" OS_VERSION="$OS_VERSION" \
        SUITE="$SUITE" SUITE_VERSION="$SUITE_VERSION" \
        LIVE_USER="$LIVE_USER" HOSTNAME_LIVE="$HOSTNAME_LIVE" BUILD_DATE="$BUILD_DATE" \
        "$@"
}

apt_install() {
    in_chroot apt-get install -y --no-install-recommends \
        -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "$@"
}

# --- stages ------------------------------------------------------------------

bootstrap() {
    if [ -f "$WORK/.bootstrapped" ]; then
        log "Reusing bootstrapped system in $ROOTFS (CLEAN=1 to rebuild)"
        return
    fi
    log "Bootstrapping Ubuntu $SUITE_VERSION ($SUITE) from $MIRROR"
    rm -rf "$ROOTFS"
    mkdir -p "$ROOTFS"
    debootstrap --arch="$ARCH" --variant=minbase \
        --components=main,restricted,universe,multiverse \
        "$SUITE" "$ROOTFS" "$MIRROR"

    : > "$ROOTFS/etc/apt/sources.list"
    cat > "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources" <<EOF
Types: deb
URIs: $MIRROR
Suites: $SUITE $SUITE-updates $SUITE-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: $SECURITY_MIRROR
Suites: $SUITE-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
    echo "$HOSTNAME_LIVE" > "$ROOTFS/etc/hostname"
    touch "$WORK/.bootstrapped"
    rm -f "$WORK/.packaged"
}

install_packages() {
    mount_chroot
    local list_sum
    list_sum=$(sha256sum "$OS_DIR/config/packages.list" | cut -d' ' -f1)
    if [ "$(cat "$WORK/.packaged" 2>/dev/null)" = "$list_sum" ]; then
        log "Reusing installed packages (CLEAN=1 to rebuild)"
        return
    fi
    log "Installing the desktop, kernel and applications"
    in_chroot debconf-set-selections <<'EOF'
keyboard-configuration keyboard-configuration/layoutcode string us
keyboard-configuration keyboard-configuration/modelcode string pc105
tzdata tzdata/Areas select Etc
tzdata tzdata/Zones/Etc select UTC
grub-pc grub-pc/install_devices_empty boolean true
EOF
    in_chroot apt-get update
    in_chroot apt-get -y \
        -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold full-upgrade
    # shellcheck disable=SC2046
    apt_install $(read_list "$OS_DIR/config/packages.list")
    echo "$list_sum" > "$WORK/.packaged"
}

build_jungey_deb() {
    log "Packaging the Jungey assistant"
    local version jar stage
    version=$(sed -n 's:^  <version>\(.*\)</version>:\1:p' "$REPO_DIR/pom.xml" | head -1)
    jar=${JUNGEY_JAR:-}
    if [ -z "$jar" ]; then
        command -v mvn >/dev/null || die "Maven not found; install JDK 21 + Maven or set JUNGEY_JAR"
        # Maven runs as whoever owns the checkout, so root does not end up
        # owning target/ and ~/.m2 in someone's home directory.
        local owner
        owner=$(stat -c %U "$REPO_DIR")
        if [ "$owner" != root ] && command -v sudo >/dev/null; then
            sudo -u "$owner" -H mvn -q -B -f "$REPO_DIR/pom.xml" -DskipTests package
        else
            mvn -q -B -f "$REPO_DIR/pom.xml" -DskipTests package
        fi
        jar="$REPO_DIR/target/jungey-$version.jar"
    fi
    [ -f "$jar" ] || die "Jungey jar not found at $jar"

    stage=$DEBS/jungey
    rm -rf "$stage"
    cp -a "$OS_DIR/debs/jungey" "$stage"
    sed -i "s/@VERSION@/$version/" "$stage/DEBIAN/control"
    install -Dm644 "$jar" "$stage/usr/share/jungey/jungey.jar"
    local size
    for size in 16 32 48 128 256; do
        install -Dm644 "$REPO_DIR/src/main/resources/icons/jungey-$size.png" \
            "$stage/usr/share/icons/hicolor/${size}x${size}/apps/jungey.png"
    done
    dpkg-deb --root-owner-group -Zxz --build "$stage" "$DEBS/jungey_${version}_amd64.deb" >/dev/null
}

build_live_deb() {
    log "Packaging the live session and installer settings"
    local stage=$DEBS/jungey-live
    rm -rf "$stage"
    cp -a "$OS_DIR/debs/jungey-live" "$stage"
    sed -i -e "s/@VERSION@/$OS_VERSION/" "$stage/DEBIAN/control"
    local branding=$stage/etc/calamares/branding/jungey
    sed -i -e "s/@OS_NAME@/$OS_NAME/g" -e "s/@OS_VERSION@/$OS_VERSION/g" \
        -e "s/@SUITE@/$SUITE/g" -e "s/@SUITE_VERSION@/$SUITE_VERSION/g" \
        "$branding/branding.desc" "$stage/usr/share/applications/jungey-install.desktop"
    install -m644 "$REPO_DIR/src/main/resources/icons/jungey-256.png" "$branding/logo.png"
    install -m644 "$REPO_DIR/src/main/resources/icons/jungey-48.png" "$branding/icon.png"
    rsvg-convert -w 320 -h 320 "$OS_DIR/artwork/reactor.svg" -o "$branding/welcome.png"

    # The installer strips the live-only packages from the installed system.
    {
        echo "# Generated by build.sh from config/packages-live.list."
        echo "backend: apt"
        echo "update_db: false"
        echo "operations:"
        echo "  - remove:"
        read_list "$OS_DIR/config/packages-live.list" | sed 's/^/      - /'
    } > "$stage/etc/calamares/modules/packages.conf"
    dpkg-deb --root-owner-group -Zxz --build "$stage" "$DEBS/jungey-live_${OS_VERSION}_all.deb" >/dev/null
}

customize() {
    mount_chroot
    rm -rf "$DEBS"
    mkdir -p "$DEBS"
    build_jungey_deb
    build_live_deb

    log "Installing Jungey and the live session"
    rm -rf "$ROOTFS/tmp/debs"
    cp -r "$DEBS" "$ROOTFS/tmp/debs"
    in_chroot apt-get update
    local live_pkgs debs=()
    live_pkgs=$(read_list "$OS_DIR/config/packages-live.list" | grep -v '^jungey-live$' | tr '\n' ' ')
    for f in "$DEBS"/*.deb; do debs+=("/tmp/debs/$(basename "$f")"); done
    # shellcheck disable=SC2086
    apt_install --reinstall "${debs[@]}" $live_pkgs
    rm -rf "$ROOTFS/tmp/debs"

    log "Applying the Jungey OS look and settings"
    rsync -rlK --chown=root:root --chmod=D755 "$OS_DIR/overlay/" "$ROOTFS/"
    install -Dm644 "$OS_DIR/artwork/wallpaper.svg" "$ROOTFS/usr/share/backgrounds/jungey/jungey-default.svg"
    rsvg-convert -w 2560 -h 1440 "$OS_DIR/artwork/wallpaper.svg" \
        -o "$ROOTFS/usr/share/backgrounds/jungey/jungey-default.png"
    install -Dm644 "$REPO_DIR/src/main/resources/icons/jungey-256.png" \
        "$ROOTFS/usr/share/pixmaps/jungey-os.png"

    local hook
    rm -rf "$ROOTFS/tmp/hooks"
    cp -r "$OS_DIR/hooks" "$ROOTFS/tmp/hooks"
    for hook in "$ROOTFS"/tmp/hooks/*.sh; do
        log "Hook: $(basename "$hook")"
        in_chroot /bin/bash -e "/tmp/hooks/$(basename "$hook")"
    done
    rm -rf "$ROOTFS/tmp/hooks"
}

make_squashfs() {
    log "Collecting the kernel and initramfs"
    rm -rf "$ISO"
    mkdir -p "$ISO/casper" "$ISO/.disk" "$ISO/boot/grub" "$ISO/EFI/boot"
    local kernel initrd
    kernel=$(find "$ROOTFS/boot" -maxdepth 1 -name 'vmlinuz-*' | sort -V | tail -1)
    initrd=$(find "$ROOTFS/boot" -maxdepth 1 -name 'initrd.img-*' | sort -V | tail -1)
    [ -n "$kernel" ] && [ -n "$initrd" ] || die "no kernel or initramfs in $ROOTFS/boot"
    cp "$kernel" "$ISO/casper/vmlinuz"
    cp "$initrd" "$ISO/casper/initrd"

    in_chroot dpkg-query -W --showformat='${Package}\t${Version}\n' > "$ISO/casper/filesystem.manifest"

    umount_chroot
    # The installer builds a fresh initramfs for the installed system; carrying
    # this one in the squashfs as well would only add ~70 MB.
    local held=$WORK/held-initrd
    rm -rf "$held"; mkdir -p "$held"
    mv "$ROOTFS"/boot/initrd.img-* "$held/"

    log "Compressing the root filesystem ($SQUASHFS_COMP) - this is the slow part"
    local comp=(-comp "$SQUASHFS_COMP")
    [ "$SQUASHFS_COMP" = xz ] && comp+=(-Xbcj x86 -b 1M)
    [ "$SQUASHFS_COMP" = zstd ] && comp+=(-Xcompression-level 19 -b 1M)
    # -e takes every argument after it as an exclude, so it has to come last.
    mksquashfs "$ROOTFS" "$ISO/casper/filesystem.squashfs" -noappend -no-progress \
        "${comp[@]}" -wildcards -e 'var/cache/apt/archives/*.deb' 'tmp/*'
    restore_initrd
    du -sx --block-size=1 "$ROOTFS" | cut -f1 > "$ISO/casper/filesystem.size"
}

make_bootloader() {
    log "Setting up BIOS and UEFI boot"
    local grub_i386=$ROOTFS/usr/lib/grub/i386-pc

    # --- the ISO's own file tree ---
    printf '%s %s "%s" - Release %s (%s)\n' "$OS_NAME" "$OS_VERSION" "$SUITE" "$ARCH" "$BUILD_DATE" > "$ISO/.disk/info"
    echo "full_cd/single" > "$ISO/.disk/cd_type"
    touch "$ISO/.disk/base_installable"
    sed -e "s/@OS_NAME@/$OS_NAME/g" -e "s/@ISO_UUID@/$ISO_UUID/g" "$OS_DIR/iso/grub.cfg" > "$ISO/boot/grub/grub.cfg"
    mkdir -p "$ISO/boot/grub/fonts"
    cp "$ROOTFS/usr/share/grub/unicode.pf2" "$ISO/boot/grub/fonts/"

    # --- BIOS: GRUB core image for El Torito, modules alongside ---
    mkdir -p "$ISO/boot/grub/i386-pc"
    cp "$grub_i386"/*.mod "$grub_i386"/*.lst "$ISO/boot/grub/i386-pc/"
    mount_chroot
    in_chroot grub-mkimage -O i386-pc-eltorito -p /boot/grub -o /tmp/eltorito.img \
        biosdisk iso9660 part_msdos part_gpt search search_fs_uuid normal configfile
    mv "$ROOTFS/tmp/eltorito.img" "$ISO/boot/grub/i386-pc/eltorito.img"
    umount_chroot

    # --- UEFI: Microsoft-signed shim -> Canonical-signed GRUB, so Secure Boot
    # machines boot it without changes. The stub config points GRUB at the ISO.
    local shim=$ROOTFS/usr/lib/shim/shimx64.efi.signed.latest
    [ -f "$shim" ] || shim=$ROOTFS/usr/lib/shim/shimx64.efi.signed
    local grub_efi=$ROOTFS/usr/lib/grub/x86_64-efi-signed/gcdx64.efi.signed
    local mm=$ROOTFS/usr/lib/shim/mmx64.efi
    for f in "$shim" "$grub_efi" "$mm"; do [ -f "$f" ] || die "missing EFI binary $f"; done

    cat > "$WORK/grub-stub.cfg" <<EOF
search --no-floppy --set=root --fs-uuid $ISO_UUID
set prefix=(\$root)/boot/grub
configfile \$prefix/grub.cfg
EOF
    cp "$shim" "$ISO/EFI/boot/bootx64.efi"
    cp "$grub_efi" "$ISO/EFI/boot/grubx64.efi"
    cp "$mm" "$ISO/EFI/boot/mmx64.efi"
    cp "$WORK/grub-stub.cfg" "$ISO/EFI/boot/grub.cfg"

    local efi_img=$WORK/efi.img
    rm -f "$efi_img"
    mkfs.vfat -C -n JUNGEY_EFI "$efi_img" 10240 >/dev/null
    mmd -i "$efi_img" ::/EFI ::/EFI/boot ::/boot ::/boot/grub
    mcopy -i "$efi_img" "$shim" ::/EFI/boot/bootx64.efi
    mcopy -i "$efi_img" "$grub_efi" ::/EFI/boot/grubx64.efi
    mcopy -i "$efi_img" "$mm" ::/EFI/boot/mmx64.efi
    mcopy -i "$efi_img" "$WORK/grub-stub.cfg" ::/EFI/boot/grub.cfg
    mcopy -i "$efi_img" "$WORK/grub-stub.cfg" ::/boot/grub/grub.cfg
}

make_iso() {
    log "Writing $ISO_NAME"
    mkdir -p "$OUT"
    rm -f "$OUT/$ISO_NAME" "$OUT/$ISO_NAME.sha256"
    # Hybrid layout, the same as Ubuntu's own images: bootable as a DVD on BIOS
    # (El Torito + GRUB) and UEFI (El Torito EFI image), and when written raw to
    # a USB stick on either (MBR boot code + a GPT EFI system partition).
    xorriso -as mkisofs -r -J -joliet-long \
        -V "$ISO_LABEL" --modification-date="$ISO_STAMP" \
        -o "$OUT/$ISO_NAME" \
        --grub2-mbr "$ROOTFS/usr/lib/grub/i386-pc/boot_hybrid.img" \
        -partition_offset 16 --mbr-force-bootable \
        -append_partition 2 28732ac11ff8d211ba4b00a0c93ec93b "$WORK/efi.img" \
        -appended_part_as_gpt \
        -iso_mbr_part_type a2a0d0ebe5b9334487c068b6b72699c7 \
        -c /boot.catalog \
        -b /boot/grub/i386-pc/eltorito.img \
            -no-emul-boot -boot-load-size 4 -boot-info-table --grub2-boot-info \
        -eltorito-alt-boot \
        -e '--interval:appended_partition_2:::' -no-emul-boot \
        "$ISO"
    (cd "$OUT" && sha256sum "$ISO_NAME" > "$ISO_NAME.sha256")
    log "Done: $OUT/$ISO_NAME ($(du -h "$OUT/$ISO_NAME" | cut -f1))"
}

main() {
    check_host
    if [ "${CLEAN:-0}" = 1 ]; then
        umount_chroot
        rm -rf "$WORK"
    fi
    mkdir -p "$WORK"
    bootstrap
    install_packages
    # "rootfs" stops once the packages are in, for iterating on the later stages.
    [ "${1:-}" = rootfs ] && return
    customize
    make_squashfs
    make_bootloader
    make_iso
}

main "$@"
