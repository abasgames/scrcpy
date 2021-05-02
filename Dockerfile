
FROM archlinux

# Install system packages
# NOTE: some packages in ownstuff seem to be borked, or at least out-of-date

RUN echo 'Server = http://mirror.rackspace.com/archlinux/$repo/os/$arch' > /etc/pacman.d/mirrorlist && \
    echo -e '\n\
[multilib]\n\
Include = /etc/pacman.d/mirrorlist\n\
\n\
[pkgbuilder]\n\
Server = https://pkgbuilder-repo.chriswarrick.com/\n\
\n\
' >> /etc/pacman.conf && \
    pacman-key --init && \
    pacman-key --recv-keys 5EAAEA16 && \
    pacman-key --lsign-key 5EAAEA16 && \
    pacman -Syu --noconfirm android-tools base-devel jdk8-openjdk pkgbuilder unzip wget && \
    useradd -m user && \
    echo 'user ALL=(ALL:ALL) NOPASSWD: ALL' >> /etc/sudoers && \
    echo 'Defaults env_keep += "ftp_proxy http_proxy https_proxy no_proxy"' >> /etc/sudoers

# Install AUR packages

WORKDIR /tmp
RUN su user -c 'pkgbuilder --noconfirm android-sdk android-sdk-build-tools-29.0.2 android-platform-30 {,mingw-w64-}{ffmpeg,meson}'

# Build scrcpy

ADD --chown=user:user . /scrcpy
WORKDIR /scrcpy
USER user
RUN mkdir build-linux64 && \
    cd build-linux64 && \
    meson && \
    ANDROID_SDK_ROOT=/opt/android-sdk ninja
RUN mkdir build-win64 && \
    cd build-win64 && \
    x86_64-w64-mingw32-meson && \
    ANDROID_SDK_ROOT=/opt/android-sdk ninja

# Package scrcpy

RUN DIST=dist/scrcpy-win64; \
    mkdir -p ${DIST} && \
    wget "https://dl.google.com/android/repository/platform-tools_r31.0.2-windows.zip" && \
    unzip "platform-tools_r31.0.2-windows.zip" && \
    cp platform-tools/{AdbWin{,Usb}Api.dll,adb.exe} ${DIST} && \
    cp build-win64/server/scrcpy-server ${DIST} && \
    scripts/copy-libs.sh build-win64/app/scrcpy.exe ${DIST} && \
    cd dist && \
    tar -cJf scrcpy-win64.tar.xz scrcpy-win64 && \
    rm -r scrcpy-win64

RUN DIST=dist/scrcpy-linux64; \
    mkdir -p ${DIST} && \
    scripts/copy-libs.sh /usr/bin/adb ${DIST} && \
    cp build-linux64/server/scrcpy-server ${DIST} && \
    scripts/copy-libs.sh build-linux64/app/scrcpy ${DIST} && \
    cd dist && \
    tar -cJf scrcpy-linux64.tar.xz scrcpy-linux64 && \
    rm -r scrcpy-linux64
