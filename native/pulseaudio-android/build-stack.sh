#!/usr/bin/env bash
# Cross-compile the PulseAudio 13.0 stack for Android arm64, adapted from BrunoSX's
# brunodev85/pulseaudio-android (stock upstream PA + ac_cv_* bionic overrides — no PA source patches).
# Uses RELEASE TARBALLS (pre-generated ./configure) so no bootstrap/autogen/help2man tooling is needed.
# Produces the exact 13.0 ABI we already bundle → drop-in.
#
# Inputs (env): NDK_PATH. Output: $OUT/  (client libs + daemon + core modules) for arm64.
set -euo pipefail

ARCH=arm64
BUILDCHAIN=aarch64-linux-android
API=26
PA_VER=13.0
LIBTOOL_VER=2.4.6
LIBSNDFILE_VER=1.0.31

BASE_DIR="$PWD"
SRC_DIR="$BASE_DIR/.src"
ROOT_DIR="$BASE_DIR/root-$ARCH"
OUT="$BASE_DIR/output/$ARCH"
: "${NDK_PATH:?set NDK_PATH to the Android NDK root}"

export PATH="$ROOT_DIR/bin:$PATH"
export PKG_CONFIG_PATH="$ROOT_DIR/lib/pkgconfig"
# PA 13.0 / libsndfile 1.0.31 are legacy C. clang 16+ promotes these to hard ERRORS by default;
# the older clang Bruno built with treated them as warnings. Downgrade so the build matches the
# compiler behavior this code was written for (does not touch our own module, built separately).
LEGACY_C="-Wno-error=implicit-function-declaration -Wno-error=implicit-int -Wno-error=int-conversion -Wno-error=incompatible-function-pointer-types -Wno-error=incompatible-pointer-types -Wno-error=deprecated-non-prototype"
export CFLAGS="-O2 -I$ROOT_DIR/include $LEGACY_C"
export CPPFLAGS="-I$ROOT_DIR/include"
export LDFLAGS="-L$ROOT_DIR/lib"

# bionic quirk overrides (from Bruno's main-build.sh) so PA's configure accepts the NDK sysroot.
export ALLOW_UNRESOLVED_SYMBOLS=1
# mkfifo IS in bionic, and has been since API 21; this builds at 26. Carried over as "no" from an
# older script, it made configure drop module-pipe-sink and module-pipe-source from the build
# entirely - which is why the bundle ended up with a 17.0 pipe-source from somewhere else that this
# 13.0 daemon refuses to load, and why the Steam client reported no microphone with everything else
# correctly wired behind it. The only sign was "checking for mkfifo... no" in a log nobody reads.
export ac_cv_func_mkfifo=yes
export ac_cv_func_getuid=no
export ax_cv_PTHREAD_PRIO_INHERIT=no
export ac_cv_header_glob_h=no
export ac_cv_func_malloc_0_nonnull=yes
export ac_cv_func_realloc_0_nonnull=yes
export ac_cv_lib_ltdl_lt_dladvise_init=yes
# NDK r27's sysroot ships <execinfo.h> but backtrace()/backtrace_symbols() are only __INTRODUCED_IN(33);
# at API 26 they don't exist in libc, so PA's pa_backtrace links fail. Tell configure execinfo is
# absent (true for our target) → pa_backtrace becomes a no-op and the references vanish.
export ac_cv_header_execinfo_h=no

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TOOLCHAIN/${BUILDCHAIN}${API}-clang"
export CXX="$TOOLCHAIN/${BUILDCHAIN}${API}-clang++"
export AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" STRIP="$TOOLCHAIN/llvm-strip"
test -x "$CC" || { echo "CC not found: $CC"; ls "$TOOLCHAIN" | grep -i clang | head; exit 1; }

mkdir -p "$SRC_DIR" "$ROOT_DIR"
# fetch DEST URL [URL...] — tries each mirror with a browser UA (some hosts 418 the default curl UA).
fetch() {
  local dest="$1"; shift
  local url
  for url in "$@"; do
    echo "fetch $url"
    if curl -fsSL --retry 3 --retry-delay 2 -A "Mozilla/5.0 (X11; Linux x86_64)" -o "$dest" "$url"; then
      return 0
    fi
    echo "  ...mirror failed, trying next"
  done
  echo "ERROR: all mirrors failed for $dest"; return 1
}

# --- libltdl (runtime module-loader lib + ltdl.h header the PA daemon needs) ---
# Build the FULL libtool from the tarball (out-of-tree, doc tools stubbed), as Bruno does — this
# installs libltdl.so AND ltdl.h into the prefix. Building only the libltdl subdir skipped the header,
# so PA's configure failed with "Unable to find libltdl version 2 / ltdl.h file not found".
if [ ! -e "$ROOT_DIR/include/ltdl.h" ]; then
  cd "$SRC_DIR"
  [ -f "libtool-$LIBTOOL_VER.tar.gz" ] || fetch "libtool-$LIBTOOL_VER.tar.gz" \
    "https://ftp.gnu.org/gnu/libtool/libtool-$LIBTOOL_VER.tar.gz" \
    "https://mirrors.kernel.org/gnu/libtool/libtool-$LIBTOOL_VER.tar.gz"
  rm -rf "libtool-$LIBTOOL_VER"; tar xf "libtool-$LIBTOOL_VER.tar.gz"
  cd "libtool-$LIBTOOL_VER"
  rm -rf "build-$ARCH"; mkdir -p "build-$ARCH"; cd "build-$ARCH"
  ../configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" --enable-ltdl-install --enable-shared \
    HELP2MAN=/bin/true MAKEINFO=/bin/true
  make -j"$(nproc)"; make install
  test -e "$ROOT_DIR/include/ltdl.h" || { echo "ltdl.h still missing after libtool install"; exit 1; }
fi

# --- libsndfile (PA optional dep; keep for parity with our shipped bundle) ---
if [ ! -e "$ROOT_DIR/lib/libsndfile.so" ]; then
  cd "$SRC_DIR"
  [ -f "libsndfile-$LIBSNDFILE_VER.tar.bz2" ] || fetch "libsndfile-$LIBSNDFILE_VER.tar.bz2" \
    "https://github.com/libsndfile/libsndfile/releases/download/$LIBSNDFILE_VER/libsndfile-$LIBSNDFILE_VER.tar.bz2"
  rm -rf "libsndfile-$LIBSNDFILE_VER"; tar xf "libsndfile-$LIBSNDFILE_VER.tar.bz2"
  cd "libsndfile-$LIBSNDFILE_VER"
  ./configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" --disable-external-libs --disable-alsa --disable-sqlite --disable-static --enable-shared
  make -j"$(nproc)"; make install
fi

# --- PulseAudio 13.0 (stock upstream; freedesktop 418s CI runners, so use distro orig tarballs — the
#     Debian/Ubuntu *.orig.tar.xz IS the pristine upstream tarball, extracts to pulseaudio-13.0/) ---
cd "$SRC_DIR"
[ -f "pulseaudio-$PA_VER.tar.xz" ] || fetch "pulseaudio-$PA_VER.tar.xz" \
  "http://old-releases.ubuntu.com/ubuntu/pool/main/p/pulseaudio/pulseaudio_${PA_VER}.orig.tar.xz" \
  "https://sources.debian.org/data/main/p/pulseaudio/${PA_VER}-5/pulseaudio_${PA_VER}.orig.tar.xz"
rm -rf "pulseaudio-$PA_VER"; tar xf "pulseaudio-$PA_VER.tar.xz"
test -d "pulseaudio-$PA_VER" || { echo "unexpected tarball layout:"; tar tf "pulseaudio-$PA_VER.tar.xz" | head; exit 1; }
cd "pulseaudio-$PA_VER"
rm -rf "build-$ARCH"; mkdir -p "build-$ARCH"; cd "build-$ARCH"
# PA 13.0 probes -std=gnu11 with `-pedantic -Werror` (configure.ac:172), which clang 18 false-negatives
# on a benign pedantic diagnostic even though it fully supports gnu11. Pre-seed AX_CHECK_COMPILE_FLAG's
# cache var to the truth so the fatal check passes; PA's non-fatal AX_APPEND_COMPILE_FLAGS probes then
# degrade gracefully. Cache-var name = AS_TR_SH("ax_cv_check_cflags_" + "$4" + "_" + "$1").
export ax_cv_check_cflags__pedantic__Werror__std_gnu11=yes
../configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" \
  --disable-static --enable-shared --disable-rpath --disable-nls --disable-x11 --disable-oss-wrapper \
  --disable-alsa --disable-esound --disable-waveout --disable-glib2 --disable-gtk3 --disable-gconf \
  --disable-avahi --disable-jack --disable-asyncns --disable-tcpwrap --disable-lirc --disable-dbus \
  --disable-bluez5 --disable-udev --disable-openssl --disable-manpages --disable-samplerate \
  --without-speex --with-database=simple --disable-orc --without-caps --without-fftw \
  --disable-systemd-daemon --disable-systemd-login --disable-systemd-journal --disable-webrtc-aec \
  --disable-tests --disable-neon-opt --disable-gsettings \
  || { echo "=== PA configure FAILED; config.log tail ==="; grep -nE "std=gnu11|gnu11|error:|clang" config.log | tail -60; exit 1; }
make -j"$(nproc)"
make install

# --- collect the runtime set (mirrors Bruno's output layout) ---
rm -rf "$OUT"; mkdir -p "$OUT/modules"
cp -a "$ROOT_DIR/bin/pulseaudio"                               "$OUT/libpulseaudio.so"
cp -a "$ROOT_DIR"/lib/pulseaudio/libpulsecommon-*.so           "$OUT/"
cp -a "$ROOT_DIR"/lib/pulseaudio/libpulsecore-*.so             "$OUT/"
cp -a "$ROOT_DIR/lib/libpulse.so"                              "$OUT/libpulse.so"
cp -a "$ROOT_DIR/lib/libsndfile.so"                            "$OUT/libsndfile.so"
cp -a "$ROOT_DIR/lib/libltdl.so"                               "$OUT/libltdl.so"
cp -a "$ROOT_DIR"/lib/pulse-*/modules/libprotocol-native.so           "$OUT/modules/"
cp -a "$ROOT_DIR"/lib/pulse-*/modules/module-native-protocol-unix.so  "$OUT/modules/"
# The microphone the Steam client sees. PulseAudio refuses a module built against another
# release, and the copy that was in the bundle came from 17.0 while this daemon is 13.0 - so it
# was rejected on sight and the client reported no input device, with nothing in any log to say
# why. Taking it from this build is the whole fix. Fail loudly rather than ship the gap again.
for m in module-pipe-source module-pipe-sink; do
  src=$(echo "$ROOT_DIR"/lib/pulse-*/modules/$m.so)
  [ -f "$src" ] || { echo "ERROR: $m.so was not built - the client would have no microphone"; exit 1; }
  cp -a "$src" "$OUT/modules/"
done
echo "stack built -> $OUT"; ls -la "$OUT" "$OUT/modules"
# expose the extracted PA source path for build-module.sh
echo "$SRC_DIR/pulseaudio-$PA_VER" > "$BASE_DIR/.pa_src_path"
