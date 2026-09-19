package pro.xiangyu.cashierhelper.analysis

import pro.xiangyu.cashierhelper.network.ConsumptionItem
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

object ConsumptionSummaryFormatter {
    fun format(items: List<ConsumptionItem>): String {
        if (items.isEmpty()) return "分析成功，未识别出消费明细"

        val currencies = linkedMapOf<String?, LinkedHashMap<String, BigDecimal>>()
        items.forEach { item ->
            val currency = item.currency?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
            val category = item.category?.trim()?.takeIf(String::isNotEmpty) ?: "未分类"
            val categories = currencies.getOrPut(currency) { linkedMapOf() }
            categories[category] = categories.getOrDefault(category, BigDecimal.ZERO).add(item.amount)
        }

        return currencies.entries.joinToString("；") { (code, categories) ->
            val total = categories.values.fold(BigDecimal.ZERO, BigDecimal::add)
            val prefix = amountPrefix(code)
            val details = categories.entries.joinToString("；") { (category, amount) ->
                "$category $prefix${formatAmount(amount, code)}"
            }
            "消费$prefix${formatAmount(total, code)}：$details"
        }
    }

    private fun amountPrefix(code: String?): String {
        if (code == null) return "未标币种 "
        return runCatching {
            Currency.getInstance(code).getSymbol(Locale.SIMPLIFIED_CHINESE)
        }.getOrElse { "$code " }
    }

    private fun formatAmount(amount: BigDecimal, code: String?): String {
        val currencyDigits = code?.let {
            runCatching { Currency.getInstance(it).defaultFractionDigits }.getOrNull()
        }?.takeIf { it >= 0 } ?: 2
        val normalized = amount.stripTrailingZeros()
        val scale = maxOf(currencyDigits, normalized.scale().coerceAtLeast(0))
        return amount.setScale(scale).toPlainString()
    }
}
