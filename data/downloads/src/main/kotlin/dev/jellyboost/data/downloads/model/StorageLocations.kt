package dev.jellyboost.data.downloads.model

/**
 * Everything the storage-location picker in Settings needs (docs/PLAN.md, "Screens" → Settings).
 *
 * @property volumes mounted volumes, primary first. A volume that is not there is simply absent,
 *   which is what makes "no SD card" render as one option instead of a disabled row.
 * @property activeVolumeId where downloads are being written **now** — the fallback volume, not the
 *   stored choice, when the two differ.
 * @property selectedVolumeMissing `true` when the chosen volume is unmounted and [activeVolumeId]
 *   is a fallback; the screen says so rather than showing a selection that is not in force.
 * @property downloadCount how many download rows exist, which is what decides whether changing the
 *   location needs the delete-and-switch confirmation (docs/PLAN.md's v1 policy).
 */
data class StorageLocations(
    val volumes: List<StorageVolumeOption> = emptyList(),
    val activeVolumeId: String? = null,
    val selectedVolumeMissing: Boolean = false,
    val downloadCount: Int = 0,
)

/**
 * One choosable volume.
 *
 * @property description the platform's own name, already localised ("SD card"); `null` when it will
 *   not say, and the UI then names it from [isRemovable].
 * @property path where files would land — shown so the answer to "where did my downloads go" is on
 *   screen and verifiable with a file manager.
 */
data class StorageVolumeOption(
    val id: String,
    val description: String?,
    val isRemovable: Boolean,
    val path: String,
    val availableBytes: Long,
)
