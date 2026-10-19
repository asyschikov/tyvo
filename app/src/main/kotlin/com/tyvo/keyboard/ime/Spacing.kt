package com.tyvo.keyboard.ime

/**
 * Whether dictated text placed right after [prev] needs a space in front.
 *
 * Join onto a word or a closing mark, but not onto an opening bracket, an
 * existing space, or a newline. Shared by the keyboard and the floating mic
 * so text lands the same way whichever one put it there.
 */
fun joinsWithSpace(prev: Char): Boolean =
    prev.isLetterOrDigit() || prev in ".,!?;:\")"
