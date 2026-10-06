package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Process-wide Gson instances; Gson is thread-safe, so no owner needs its own copy. */
object SharedGson {
    val plain: Gson = Gson()

    /** Pretty-printing variant for user-facing documents (library files, exported reports). */
    val pretty: Gson = GsonBuilder().setPrettyPrinting().create()
}
