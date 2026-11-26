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

    /** Booted the patched new slot without root. Arg: folder with the new build's images. */
    ROOT_LOST_AFTER_UPDATE,

    /** Root lost and the running slot is unclear. Arg: backup folder. */
    ROOT_LOST_BACKUPS,
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

    /** [Phase.FAILED]: the current slot did not verify. Arg: folder with the running build's images. */
    CURRENT_SLOT_FAILED,

    /** [Phase.FAILED]: the inactive (new) slot did not verify. Arg: folder with the new build's stock image. */
    NEW_SLOT_FAILED,
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
