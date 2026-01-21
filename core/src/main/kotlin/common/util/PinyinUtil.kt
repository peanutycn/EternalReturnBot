package cn.luorenmu.common.util

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * Lightweight pinyin utilities for homophone-friendly matching.
 */
object PinyinUtil {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    fun toPinyin(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder(text.length * 2)
        for (ch in text) {
            val arr = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch, format) }.getOrNull()
            val py = arr?.firstOrNull()
            if (!py.isNullOrBlank()) sb.append(py) else sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }
}

fun String.toPinyin(): String = PinyinUtil.toPinyin(this)
