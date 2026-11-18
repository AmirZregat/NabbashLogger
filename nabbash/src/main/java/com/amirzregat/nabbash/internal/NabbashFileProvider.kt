package com.amirzregat.nabbash.internal

import androidx.core.content.FileProvider

/**
 * A FileProvider of Nabbash's own, so its authority and paths can never collide with
 * a FileProvider the host app declares. It can only serve files from
 * `cache/nabbash_exports/`, and only to the app the user picked in the share sheet.
 */
internal class NabbashFileProvider : FileProvider()
