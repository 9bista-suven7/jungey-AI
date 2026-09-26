# Jungey OS

A lightweight Linux desktop for 64-bit PCs, with the Jungey assistant built in.
It boots from a USB stick into a live desktop you can try without touching your
disk, and a graphical installer puts it on the machine for good.

| | |
|---|---|
| Base | Ubuntu 24.04 LTS (security updates until 2029), hardware-enablement kernel |
| Desktop | Xfce 4.18 with LightDM, dark Greybird theme |
| Apps | Jungey, GNOME Web browser, Thunar files, Mousepad, Ristretto images, Atril PDF, Parole media, Synaptic package manager |
| Installer | Calamares: erase disk, install alongside Windows, or manual partitioning, optional disk encryption |
| Boots on | BIOS and UEFI, with Secure Boot on or off |
| Needs | 64-bit x86 PC, 2 GB RAM (1 GB works, slowly), 12 GB disk, a 4 GB+ USB stick |

## 1. Get the ISO

Download `jungey-os-1.0-amd64.iso` from the
[latest build](https://github.com/9bista-suven7/jungey-AI/releases/tag/jungey-os-latest)
on the Releases page. GitHub Actions rebuilds it whenever the OS or Jungey
changes.

Check the download against the `.sha256` file next to it:

```bash
sha256sum -c jungey-os-1.0-amd64.iso.sha256          # Linux
certutil -hashfile jungey-os-1.0-amd64.iso SHA256    # Windows: compare by eye
```

## 2. Write it to a USB stick

Everything on the stick is erased.

- **Windows, macOS or Linux:** [balenaEtcher](https://etcher.balena.io/) — pick the
  ISO, pick the stick, Flash.
- **Windows:** [Rufus](https://rufus.ie/) also works; when it asks, choose
  **DD image mode**, not ISO mode.
- **Linux terminal:** find the stick with `lsblk` (say it is `/dev/sdb` — check
  twice, this overwrites whatever it names), then

  ```bash
  sudo dd if=jungey-os-1.0-amd64.iso of=/dev/sdb bs=4M status=progress oflag=sync
  ```

## 3. Boot from the stick

Plug it in, restart, and press the boot-menu key while the maker's logo shows:
usually **F12** (Dell, Lenovo, Acer), **F9** (HP), **F8** or **Esc** (ASUS),
**F11** (MSI), or hold **Option** on an Intel Mac. Choose the USB stick — on UEFI
machines it may appear twice; pick the one marked UEFI.

The Jungey OS menu appears. **Try or install Jungey OS** starts the live desktop
in about a minute. If the screen stays black, reboot and choose
**safe graphics**.

Secure Boot can stay on: the stick uses Ubuntu's signed boot loader.

## 4. Install

On the live desktop, double-click **Install Jungey OS**. The installer asks for
language, time zone, keyboard, where to install, and your name and password,
then copies the system (5–15 minutes). On the disk page:

- **Erase disk** — the whole disk becomes Jungey OS. Everything on it is lost.
- **Install alongside** — keeps Windows or another system; drag the divider to
  choose how much space each gets. A menu at startup lets you pick between them.
- **Encrypt system** — asks for a passphrase at every boot; protects your files if
  the laptop is lost.

Back up anything important before installing. Remove the stick when told to and
restart.

## After installing

- **Updates:** security updates install themselves daily. For everything else,
  `sudo apt update && sudo apt upgrade`, or Synaptic → Reload → Mark All
  Upgrades → Apply.
- **Software:** Synaptic (menu → System) or `sudo apt install <name>` reaches the
  whole Ubuntu archive.
- **Firefox:** on Ubuntu it comes as a snap: `sudo apt install firefox` sets up
  snapd and installs it.
- **NVIDIA graphics:** `sudo apt install ubuntu-drivers-common && sudo ubuntu-drivers install`, then restart.
- **Jungey:** Super+J or the arc-reactor icon in the panel. Voice input, the
  local language model and the vision model are optional downloads — see the
  [main README](../README.md#optional-extras).
- **Keys:** Super opens the menu, Super+E files, Super+T or Ctrl+Alt+T terminal,
  Print screenshots, Ctrl+Alt+L locks.

The firmware's boot list and the GRUB menu (shown only when another system is
installed) call the entry "Ubuntu". Ubuntu's Secure Boot-signed GRUB only looks
for its files under that name, so Jungey OS keeps it rather than lose Secure
Boot.

## Try it in a virtual machine first

```bash
sudo apt install qemu-system-x86 ovmf
os/test/run-vm.sh               # BIOS
os/test/run-vm.sh --uefi --disk # UEFI, with a virtual disk to practise installing on
os/test/run-vm.sh --uefi --no-iso   # boot what you installed
```

VirtualBox and VMware work too: create a 64-bit Ubuntu VM with 4 GB of RAM and
attach the ISO as its optical drive.

## Build it yourself

On Ubuntu 24.04 (a VM or container with root is fine), from the repository root:

```bash
sudo apt install debootstrap squashfs-tools xorriso mtools dosfstools librsvg2-bin rsync \
                 openjdk-21-jdk maven
sudo ./os/build.sh
```

The ISO lands in `os/out/`. It needs about 12 GB free in `os/work/` and takes
20–40 minutes, most of it compressing the filesystem. Later runs reuse the
downloaded system; `CLEAN=1` starts over. `SQUASHFS_COMP=zstd` builds faster at
the cost of a bigger ISO.

| Path | What it holds |
|---|---|
| `build.sh` | the build, stage by stage: bootstrap, packages, Jungey and live session, squashfs, boot loaders, ISO |
| `config/os.conf` | name, version, Ubuntu release, mirror, live user |
| `config/packages.list` | everything the installed system has |
| `config/packages-live.list` | live-session-only packages the installer removes |
| `overlay/` | files copied as-is into the system: desktop session, panel, themes, LightDM, netplan, zram |
| `hooks/` | scripts run inside the new system: locale, branding, Xfce defaults, initramfs, cleanup |
| `debs/jungey/` | the Jungey assistant as a `.deb` (the jar is added at build time) |
| `debs/jungey-live/` | installer settings, branding and the live desktop's install launcher |
| `iso/grub.cfg` | the USB stick's boot menu |
| `artwork/` | wallpaper and installer artwork |
| `test/run-vm.sh` | boot the ISO in QEMU |

To add an application, put its Ubuntu package name in `config/packages.list`
and rebuild.
