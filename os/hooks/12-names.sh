# Runs inside the new system, and again from apply-look.sh on an installed one.
# Jungey OS's name where Ubuntu's would show outside os-release (10-system.sh):
# the console's login banner and lsb_release's description, which the login
# message of the day repeats. DISTRIB_ID stays Ubuntu for tools that check it.
set -euo pipefail

: "${OS_NAME:=Jungey OS}" "${OS_VERSION:=1.0}" "${SUITE:=noble}" "${SUITE_VERSION:=24.04}"

divert() {
    dpkg-divert --list "$1" | grep -q . ||
        dpkg-divert --local --rename --divert "$1.ubuntu" --add "$1"
}
divert /etc/issue
divert /etc/issue.net
divert /etc/lsb-release
printf '%s %s \\n \\l\n\n' "$OS_NAME" "$OS_VERSION" > /etc/issue
printf '%s %s\n' "$OS_NAME" "$OS_VERSION" > /etc/issue.net
cat > /etc/lsb-release <<EOF
DISTRIB_ID=Ubuntu
DISTRIB_RELEASE=$SUITE_VERSION
DISTRIB_CODENAME=$SUITE
DISTRIB_DESCRIPTION="$OS_NAME $OS_VERSION"
EOF
