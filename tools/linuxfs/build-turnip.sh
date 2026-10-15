#!/bin/bash
# Cross-builds Mesa's Turnip Vulkan driver for the Linux runtime with the KGSL backend, against
# the Arch Linux ARM rootfs build-linuxfs.sh assembled, and applies turnip/*.patch.
#
#   tools/linuxfs/build-turnip.sh <work dir> <rootfs>      -> <work dir>/libvulkan_freedreno.so
#
# Needs aarch64-linux-gnu-gcc/g++, meson >= 1.5, ninja, python3 with mako and pyyaml.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mkdir -p "${1:?work dir}" && cd "$1" && pwd)
rootfs=$(cd "${2:?rootfs}" && pwd)
version=26.2.2
sha256=eeb29ca7e56cfaa8e8a79538dcf834e3b18e501c31bef5145e959ea437cc4216
src=$work/mesa-$version

cd "$work"
if [ ! -f "$src/.patched" ]; then
  [ -s "mesa-$version.tar.xz" ] || curl -fsSL --retry 6 --retry-delay 5 --retry-all-errors -o "mesa-$version.tar.xz" "https://archive.mesa3d.org/mesa-$version.tar.xz"
  echo "$sha256  mesa-$version.tar.xz" | sha256sum -c --quiet
  rm -rf "$src" && tar -xJf "mesa-$version.tar.xz"
  for p in "$here"/turnip/*.patch; do
    patch -d "$src" -p1 < "$p"
  done
  touch "$src/.patched"
fi

cat > cross.ini <<EOF
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'pkg-config'

[properties]
sys_root = '$rootfs'
pkg_config_libdir = '$rootfs/usr/lib/pkgconfig:$rootfs/usr/share/pkgconfig'

[built-in options]
c_args = ['--sysroot=$rootfs']
cpp_args = ['--sysroot=$rootfs']
c_link_args = ['--sysroot=$rootfs', '-L$rootfs/usr/lib']
cpp_link_args = ['--sysroot=$rootfs', '-L$rootfs/usr/lib']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# Mesa's Wayland module wants a wayland-scanner >= 1.26 on the BUILD machine, and distributions
# lag it (Ubuntu 24.04 ships 1.22). Build the scanner alone — no libraries, no docs — from the
# pinned release; it takes seconds.
wl_version=1.26.0
wl_sha256=64176eaa46e4969903e286f8e5ef8331affc17fdf03ac9b58381d2b23162b7a3
host=$work/wayland-host
if [ ! -x "$host/bin/wayland-scanner" ]; then
  [ -s "wayland-$wl_version.tar.xz" ] || curl -fsSL --retry 6 --retry-delay 5 --retry-all-errors \
    -o "wayland-$wl_version.tar.xz" \
    "https://gitlab.freedesktop.org/wayland/wayland/-/releases/$wl_version/downloads/wayland-$wl_version.tar.xz"
  echo "$wl_sha256  wayland-$wl_version.tar.xz" | sha256sum -c --quiet
  rm -rf "wayland-$wl_version" && tar -xJf "wayland-$wl_version.tar.xz"
  meson setup wayland-build "wayland-$wl_version" --prefix "$host" --libdir lib --buildtype release \
    -Dlibraries=false -Dscanner=true -Dtests=false -Ddocumentation=false -Ddtd_validation=false
  ninja -C wayland-build install
fi

# Programs Mesa runs on the BUILD machine. Without this it asks the sysroot's pkg-config for
# wayland-scanner and gets the aarch64 binary, which the build host cannot execute; and the
# build-machine pkg-config path is where it finds the scanner built above.
cat > native.ini <<EOF
[binaries]
c = 'gcc'
cpp = 'g++'
pkg-config = '/usr/bin/pkg-config'
cmake = '/usr/bin/cmake'
wayland-scanner = '$host/bin/wayland-scanner'
glslangValidator = '$(command -v glslangValidator)'

[built-in options]
pkg_config_path = '$host/lib/pkgconfig'
EOF

[ -f build/build.ninja ] || meson setup build "$src" --cross-file cross.ini --native-file native.ini --buildtype release \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=msm,kgsl -Dgallium-drivers= -Dplatforms=wayland,x11 \
  -Dopengl=false -Dgbm=disabled -Dglx=disabled -Degl=disabled -Dllvm=disabled -Dvulkan-layers= -Dtools=
ninja -C build src/freedreno/vulkan/libvulkan_freedreno.so
aarch64-linux-gnu-strip -o libvulkan_freedreno.so build/src/freedreno/vulkan/libvulkan_freedreno.so
ls -la libvulkan_freedreno.so
