package io.github.otahelper.core

/** User-facing messages produced by the engine. The Android layer maps each to a string resource. */
enum class Msg {
    PREPARE_DONE,
    CANCEL_DONE,
    PATCH_DONE,
    REBOOTING,

    NO_ROOT,

    /** Arg: path of the saved patched image to flash with fastboot. */
    ROOT_LOST,
    NOT_AB,
    NO_KERNELSU,
    NOT_LKM,

    /** Arg: partition name. */
    UNSUPPORTED_PARTITION,
    TOOLS_MISSING,
    READ_FAILED,
    UNKNOWN_IMAGE,

    WRONG_PHASE,
    BACKUP_MISSING,
    BACKUP_INVALID,

    /** Arg: folder. */
    SAVE_FAILED,
    BACKUP_DIR_INVALID,
    STATE_CHANGED,
    FLASH_FAILED,
    VERIFY_FAILED,
    ENGINE_UNKNOWN,
    UPDATE_RUNNING,
    UPDATE_INSTALLED,
    NOT_INSTALLED,
    INSTALL_FAILED,
    UPDATE_NOT_APPLIED,
    REBOOT_FAILED,
    INTERNAL,
}

/** A message plus its optional single argument. */
data class Note(val msg: Msg, val arg: String? = null)
