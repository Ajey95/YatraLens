package com.yatralens

/** One ephemeral, phone-local preview of the latest user-selected camera capture. */
object VisualEvidenceState {
    @Volatile private var jpeg: ByteArray? = null
    @Volatile var caption: String = ""
        private set

    @Synchronized fun set(bytes: ByteArray, label: String) {
        jpeg?.fill(0)
        jpeg = bytes.copyOf()
        caption = label
    }

    @Synchronized fun copy(): ByteArray? = jpeg?.copyOf()

    @Synchronized fun clear() {
        jpeg?.fill(0)
        jpeg = null
        caption = ""
    }
}
