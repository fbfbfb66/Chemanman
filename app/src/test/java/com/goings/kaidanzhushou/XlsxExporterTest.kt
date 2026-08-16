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

    @Test fun cellReferenceSupportsColumnsBeyondZ() {
        val exporter = XlsxExporter()
        assertEquals("A1", exporter.ref(0, 1))
        assertEquals("P2", exporter.ref(15, 2))
        assertEquals("Z1", exporter.ref(25, 1))
        assertEquals("AA1", exporter.ref(26, 1))
        assertEquals("AZ3", exporter.ref(51, 3))
        assertEquals("BA1", exporter.ref(52, 1))
    }

    @Test fun advancePaymentGoesToTheChosenColumnAndLeavesTheOtherEmpty() {
        val file = File.createTempFile("kaidan-advance", ".xlsx")
        val batch = BatchEntity("batch-id", "测试批次", 1, 1, 3)
        // 单据示例：垫付款 250、运费 30、总运费 280。
        val records = listOf(
            fixture(1).copy(freight = 280.0, advancePayment = 250.0, advanceReturnType = "cashreturn"),
            fixture(2).copy(freight = 280.0, advancePayment = 250.0, advanceReturnType = "discount"),
            fixture(3).copy(freight = 30.0),
        )
        XlsxExporter().write(file, batch, records)
        XSSFWorkbook(file).use { workbook ->
            val sheet = workbook.getSheet("导入数据")
            assertEquals("cashreturn", sheet.getRow(0).getCell(18).stringCellValue)
            assertEquals("discount", sheet.getRow(0).getCell(19).stringCellValue)
            assertEquals(280.0, sheet.getRow(1).getCell(14).numericCellValue, 0.0)
            assertEquals(250.0, sheet.getRow(1).getCell(18).numericCellValue, 0.0)
            assertEquals(null, sheet.getRow(1).getCell(19))
            assertEquals(null, sheet.getRow(2).getCell(18))
            assertEquals(250.0, sheet.getRow(2).getCell(19).numericCellValue, 0.0)
            // 没有垫付款的单子两列都不写，导出结果与升级前完全一致。
            assertEquals(null, sheet.getRow(3).getCell(18))
            assertEquals(null, sheet.getRow(3).getCell(19))
        }
        file.delete()
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
            assertEquals(XlsxExporter.COLUMNS, XlsxExporter.COLUMNS.indices.map { sheet.getRow(0).getCell(it).stringCellValue })
            assertEquals(20, XlsxExporter.COLUMNS.size)
            assertEquals("v1.3", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals("pay_billing", sheet.getRow(1).getCell(15).stringCellValue)
            assertEquals("xzqh_id_38010", sheet.getRow(1).getCell(16).stringCellValue)
            assertEquals("云南省玉溪市通海县", sheet.getRow(1).getCell(17).stringCellValue)
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
        destinationText = "通海县", deliveryType = "delivery", senderName = "张三", receiverName = "李四",
        destinationUniqueKey = "xzqh_id_38010", destinationDisplay = "云南省玉溪市通海县",
        receiverMobile = if (index == 1) null else "13800000000", goodsName = "配件", packageName = null,
        quantity = index, weight = 2.5, volume = null, freight = 10.25,
        paymentType = listOf("pay_billing", "pay_arrival", "pay_receipt")[(index - 1) % 3],
    )
}
