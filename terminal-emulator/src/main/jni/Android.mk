LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# 16 KB page alignment is mandatory on Android 13+ (CLAUDE.md).
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,--gc-sections
# On aarch64 build hosts the NDK ships only Android-targeted host binaries, so
# ndk-build is driven by the distro clang, whose driver injects -lgcc (absent in
# the NDK). Suppress it there only; x86_64 hosts use the NDK clang and need none.
ifeq ($(shell uname -m),aarch64)
LOCAL_LDFLAGS += -nostdlib
endif
include $(BUILD_SHARED_LIBRARY)
