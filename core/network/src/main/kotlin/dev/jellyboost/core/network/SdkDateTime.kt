package dev.jellyboost.core.network

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Conversions between [Instant] — the only timestamp type the domain models and Room use — and the
 * `LocalDateTime` values jellyfin-sdk exposes for every date field.
 *
 * **These must not treat the SDK's `LocalDateTime` as UTC.** Its `DateTimeSerializer` (verified against
 * jellyfin-sdk 1.8.12) is zone-aware and defaults to [ZoneId.systemDefault], so an SDK `LocalDateTime` is
 * always **local wall-clock time**: handing it UTC wall-clock time stamps the device's offset onto the wrong
 * reading, and a 17:22 UTC event goes out as `17:22:57+02:00` — stored two hours early. Round-tripping
 * through [ZoneId.systemDefault] is what makes the *instant* survive.
 *
 * @param zone the zone the SDK serializer will apply; only tests ever pass it explicitly.
 */
fun Instant.toSdkDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = LocalDateTime.ofInstant(this, zone)

fun LocalDateTime.toSdkInstant(zone: ZoneId = ZoneId.systemDefault()): Instant = atZone(zone).toInstant()
