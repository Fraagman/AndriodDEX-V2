#!/bin/bash
set -e

echo "Downloading minimal VM payload (Alpine Linux rootfs)..."

# This script downloads or prepares the VM payload.
# For production, this should fetch the actual signed VM image.

# 1. Download Alpine Mini Root Filesystem
# wget https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.0-aarch64.tar.gz

# 2. Create a sparse ext4 image
# dd if=/dev/zero of=vm.img bs=1M count=1024
# mkfs.ext4 vm.img

# 3. Mount and extract Alpine rootfs
# sudo mount vm.img /mnt
# sudo tar -xzf alpine-minirootfs-3.19.0-aarch64.tar.gz -C /mnt

# 4. Install Wayland / Weston
# sudo chroot /mnt /bin/sh -c "apk update && apk add weston dbus foot"

# 5. Configure Weston to run on startup and output to virtio-gpu
# Create /mnt/etc/xdg/weston/weston.ini ...
# sudo umount /mnt

if [ ! -f vm.img ]; then
    echo "Error: vm.img not found. Please provide a valid Microdroid payload."
    exit 1
fi

echo "Done."

echo "To test on device:"
echo "adb push vm.img /data/local/tmp/vm.img"
echo "Make sure to grant necessary permissions if required."
