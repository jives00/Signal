package com.signal.match

import java.text.Normalizer

internal actual fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

private val marks = Regex("""\p{Mn}+""")

internal actual fun stripAccents(text: String): String = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
