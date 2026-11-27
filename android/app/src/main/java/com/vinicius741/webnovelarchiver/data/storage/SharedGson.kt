package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Process-wide Gson instances; Gson is thread-safe, so no owner needs its own copy. */
object SharedGson {
    /** Compact output; also the storage Gson, since nobody reads the durable library files by hand. */
    val plain: Gson = Gson()

    /** Pretty-printing variant for user-facing exported reports (diagnostics, the dev library report). */
    val pretty: Gson = GsonBuilder().setPrettyPrinting().create()
}
