package dev.jellyfinnative.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Conversions between [Instant] — the only timestamp type the domain models and Room use — and the
 * `java.time.LocalDateTime` values jellyfin-sdk exposes for every date field.
 *
 * **These must not treat the SDK's `LocalDateTime` as UTC.** The SDK's `DateTimeSerializer`
 * (verified against jellyfin-sdk 1.8.12) is zone-aware and defaults to [ZoneId.systemDefault]:
 *
 * ```
 * serialize   -> value.atZone(zoneId).format(ISO_OFFSET_DATE_TIME)
 * deserialize -> ZonedDateTime.parse(text).withZoneSameInstant(zoneId).toLocalDateTime()
 * ```
 *
 * So an SDK `LocalDateTime` is always **local wall-clock time**, and handing it UTC wall-clock time
 * makes it stamp the device's offset onto the wrong reading — the M4 bug where a 17:22 UTC event
 * went out as `17:22:57+02:00` and the server stored it two hours early (STATUS.md, "Known
 * issues"). Round-tripping through [ZoneId.systemDefault] is what makes the *instant* survive.
 *
 * @param zone the zone the SDK serializer will apply; only tests ever pass it explicitly.
 */
fun Instant.toSdkDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(this, zone)

/** Inverse of [toSdkDateTime]: reads an SDK date field back as the instant it denotes. */
fun LocalDateTime.toSdkInstant(zone: ZoneId = ZoneId.systemDefault()): Instant = atZone(zone).toInstant()
