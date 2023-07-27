
FROM archlinux

# Install system packages

RUN echo 'Server = http://mirror.rackspace.com/archlinux/$repo/os/$arch' > /etc/pacman.d/mirrorlist && \
    echo -e '\n\
[multilib]\n\
Include = /etc/pacman.d/mirrorlist\n\
\n\
[ownstuff]\n\
Server = http://martchus.no-ip.biz/repo/arch/$repo/os/$arch\n\
Server = https://ftp.f3l.de/~martchus/$repo/os/$arch\n\
\n\
[pkgbuilder]\n\
Server = https://pkgbuilder-repo.chriswarrick.com/\n\
\n\
' >> /etc/pacman.conf && \
    pacman-key --init && \
    pacman-key --recv-keys B9E36A7275FC61B464B67907E06FE8F53CDC6A4C && \
    pacman-key --lsign-key B9E36A7275FC61B464B67907E06FE8F53CDC6A4C && \
    pacman-key --recv-keys 5EAAEA16 && \
    pacman-key --lsign-key 5EAAEA16 && \
    pacman -Syu --noconfirm android-tools base-devel jdk8-openjdk {,mingw-w64-}{ffmpeg,meson} pkgbuilder unzip wget xxd && \
    useradd -m user && \
    echo 'user ALL=(ALL:ALL) NOPASSWD: ALL' >> /etc/sudoers && \
    echo 'Defaults env_keep += "ftp_proxy http_proxy https_proxy no_proxy"' >> /etc/sudoers

# Install AUR packages

WORKDIR /tmp
RUN su user -c 'pkgbuilder --noconfirm android-sdk android-sdk-build-tools-29.0.2 android-platform-30'

# Build scrcpy

ADD --chown=user:user . /scrcpy
WORKDIR /scrcpy
USER user
RUN mkdir build-linux64 && \
    cd build-linux64 && \
    meson && \
    ANDROID_SDK_ROOT=/opt/android-sdk ninja

# Package scrcpy

RUN DIST=dist/scrcpy-linux64; \
    mkdir -p ${DIST} && \
    scripts/copy-libs.sh /usr/bin/adb ${DIST} && \
    cp build-linux64/server/scrcpy-server ${DIST} && \
    scripts/copy-libs.sh build-linux64/app/scrcpy ${DIST} && \
    cd dist && \
    tar -cJf scrcpy-linux64.tar.xz scrcpy-linux64 && \
    rm -r scrcpy-linux64
