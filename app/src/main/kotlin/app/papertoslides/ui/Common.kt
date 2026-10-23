package app.papertoslides.ui


val FLAG_LABELS = mapOf(
    "low_confidence" to "Check", "no_number" to "No number", "small_text" to "Small", "too_small_to_read" to "Too small",
    "split_stitched" to "Joined", "page_break_stitched" to "Across pages", "shared_passage" to "Passage", "refers_to_passage" to "Uses passage",
    "as_picture" to "Picture", "expanded" to "Full height", "passage_separate" to "Passage slide", "bilingual_twin" to "2 languages",
)
val WARN_FLAGS = setOf("low_confidence", "no_number", "too_small_to_read", "small_text", "as_picture")
