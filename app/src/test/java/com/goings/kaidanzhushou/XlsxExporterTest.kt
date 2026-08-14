package com.goings.kaidanzhushou

import com.goings.kaidanzhushou.data.local.BatchEntity
import com.goings.kaidanzhushou.data.local.RecordEntity
import com.goings.kaidanzhushou.domain.ReviewStatus
import com.goings.kaidanzhushou.export.XlsxExporter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class XlsxExporterTest {
    @Test fun writesOneAndOneHundredRowsAsValidFormulaFreeXlsx() {
        verify(1)
        verify(100)
    }

    private fun verify(count: Int) {
        val file = File.createTempFile("kaidan-$count", ".xlsx")
        val batch = BatchEntity("batch-id", "测试批次", 1, 1, 3)
        val records = (1..count).map { index -> fixture(index) }
        XlsxExporter().write(file, batch, records)
        XSSFWorkbook(file).use { workbook ->
            assertEquals(1, workbook.numberOfSheets)
            val sheet = workbook.getSheet("导入数据")
            assertEquals(count, sheet.lastRowNum)
            assertEquals(XlsxExporter.COLUMNS, (0 until 16).map { sheet.getRow(0).getCell(it).stringCellValue })
            assertEquals("v1.1", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("pay_billing", sheet.getRow(1).getCell(15).stringCellValue)
            if (count >= 3) {
                assertEquals("pay_arrival", sheet.getRow(2).getCell(15).stringCellValue)
                assertEquals("pay_receipt", sheet.getRow(3).getCell(15).stringCellValue)
            }
            assertEquals(1.0, sheet.getRow(1).getCell(11).numericCellValue, 0.0)
            for (row in sheet) for (cell in row) assertFalse("不允许公式", cell.cellType.name == "FORMULA")
        }
        file.delete()
    }

    private fun fixture(index: Int) = RecordEntity(
        id = "record-$index", batchId = "batch-id", ordinal = index, sourceLabel = "照片 %03d".format(index),
        originalPath = "/fake/$index.jpg", capturedAt = 1, reviewStatus = ReviewStatus.CONFIRMED,
        destinationText = "杭州", deliveryType = "delivery", senderName = "张三", receiverName = "李四",
        receiverMobile = if (index == 1) null else "13800000000", goodsName = "配件", packageName = null,
        quantity = index, weight = 2.5, volume = null, freight = 10.25,
        paymentType = listOf("pay_billing", "pay_arrival", "pay_receipt")[(index - 1) % 3],
    )
}
