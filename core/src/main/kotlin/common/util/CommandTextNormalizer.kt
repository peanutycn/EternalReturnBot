package cn.luorenmu.common.util

/**
 * Normalizes common Traditional Chinese command words into the Simplified
 * forms used internally by the command router.
 */
object CommandTextNormalizer {
    private val charMap: Map<Char, Char> = mapOf(
        '查' to '查',
        '詢' to '询',
        '戰' to '战',
        '績' to '绩',
        '網' to '网',
        '頁' to '页',
        '實' to '实',
        '驗' to '验',
        '體' to '体',
        '幫' to '帮',
        '統' to '统',
        '計' to '计',
        '別' to '别',
        '名' to '名',
        '權' to '权',
        '限' to '限',
        '診' to '诊',
        '斷' to '断',
        '設' to '设',
        '置' to '置',
        '刪' to '删',
        '除' to '除',
        '反' to '反',
        '饋' to '馈',
        '員' to '员',
        '管' to '管',
        '理' to '理',
        '群' to '群',
        '全' to '全',
        '局' to '局',
        '申' to '申',
        '請' to '请',
        '無' to '无',
        '線' to '线',
        '段' to '段',
        '位' to '位',
        '路' to '路',
        '徑' to '径'
    )

    fun normalize(text: String): String =
        buildString(text.length) {
            text.forEach { append(charMap[it] ?: it) }
        }
}
