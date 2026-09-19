package pro.xiangyu.cashierhelper.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.xiangyu.cashierhelper.network.ConsumptionItem

class ConsumptionSummaryFormatterTest {
    @Test
    fun `groups one currency by category using exact decimal sums`() {
        val result = ConsumptionSummaryFormatter.format(
            listOf(
                item("99.99", "CNY", "餐饮"),
                item("0.01", "CNY", "餐饮"),
                item("23.45", "CNY", "交通"),
            ),
        )

        assertEquals("消费¥123.45：餐饮 ¥100.00；交通 ¥23.45", result)
    }

    @Test
    fun `keeps currencies separate and labels missing values`() {
        val result = ConsumptionSummaryFormatter.format(
            listOf(
                item("10", "CNY", null),
                item("2.345", "ZZZ", ""),
                item("1", null, "其他"),
            ),
        )

        assertEquals(
            "消费¥10.00：未分类 ¥10.00；消费ZZZ 2.345：未分类 ZZZ 2.345；" +
                "消费未标币种 1.00：其他 未标币种 1.00",
            result,
        )
    }

    @Test
    fun `reports empty analysis`() {
        assertEquals("分析成功，未识别出消费明细", ConsumptionSummaryFormatter.format(emptyList()))
    }

    private fun item(amount: String, currency: String?, category: String?) =
        ConsumptionItem(amount.toBigDecimal(), currency, category)
}
