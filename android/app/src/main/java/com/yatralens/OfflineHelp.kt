package com.yatralens

/** Bundled references remain readable without network; calls require a user tap. */
object OfflineHelp {
    const val SOURCE = "https://sdma.kerala.gov.in/wp-content/uploads/2026/06/Directory-wpn.pdf"
    const val REVIEWED_ON = "2026-11-21"

    data class Contact(val label: String, val number: String)

    val contacts = listOf(
        Contact("India emergency response", "112"),
        Contact("Kerala ambulance service", "108"),
        Contact("District emergency operations", "1077")
    )
}
