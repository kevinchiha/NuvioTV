package com.nuvio.tv.updater

import kotlinx.serialization.json.Json

/** Shared JSON config for persisting/restoring the cached pre-download [model.AppUpdate]. */
internal object UpdateJson {
    val json: Json = Json { ignoreUnknownKeys = true }
}
