package com.goings.kaidanzhushou.export

import com.goings.kaidanzhushou.data.local.BatchEntity
import com.goings.kaidanzhushou.data.local.RecordEntity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxExporter {
    fun write(target: File, batch: BatchEntity, records: List<RecordEntity>) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.entry("[Content_Types].xml", contentTypes())
            zip.entry("_rels/.rels", rootRels())
            zip.entry("xl/workbook.xml", workbook())
            zip.entry("xl/_rels/workbook.xml.rels", workbookRels())
            zip.entry("xl/styles.xml", styles())
            zip.entry("xl/worksheets/sheet1.xml", worksheet(batch, records))
        }
    }

    private fun worksheet(batch: BatchEntity, records: List<RecordEntity>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        append("<sheetData>")
        append("<row r=\"1\">")
        COLUMNS.forEachIndexed { column, name -> append(textCell(ref(column, 1), name)) }
        append("</row>")
        records.forEachIndexed { index, record ->
            val row = index + 2
            append("<row r=\"$row\">")
            val values = listOf(
                Cell.Text("v1.0"), Cell.Text(batch.id), Cell.Text(record.id), Cell.Text(record.sourceLabel),
                Cell.Text(record.destinationText), Cell.Text(record.deliveryType), Cell.Text(record.senderName),
                Cell.Text(record.receiverName), Cell.Text(record.receiverMobile), Cell.Text(record.goodsName),
                Cell.Text(record.packageName), Cell.Number(record.quantity), Cell.Number(record.weight),
                Cell.Number(record.volume), Cell.Number(record.freight), Cell.Text("pay_billing"),
            )
            values.forEachIndexed { column, value ->
                when (value) {
                    is Cell.Text -> append(textCell(ref(column, row), value.value.orEmpty()))
                    is Cell.Number -> value.value?.let { append(numberCell(ref(column, row), it.toString())) }
                }
            }
            append("</row>")
        }
        append("</sheetData><autoFilter ref=\"A1:P${records.size + 1}\"/>")
        append("</worksheet>")
    }

    private fun textCell(ref: String, value: String) = "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
    private fun numberCell(ref: String, value: String) = "<c r=\"$ref\" t=\"n\"><v>$value</v></c>"
    private fun ref(column: Int, row: Int) = "${('A'.code + column).toChar()}$row"
    private fun xml(value: String) = value.filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun ZipOutputStream.entry(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""
    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private fun workbook() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="导入数据" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs></styleSheet>"""

    private sealed interface Cell {
        data class Text(val value: String?) : Cell
        data class Number(val value: kotlin.Number?) : Cell
    }

    companion object {
        val COLUMNS = listOf(
            "schema_version", "batch_id", "source_record_id", "source_label", "destination_text", "delivery_type",
            "sender_name", "receiver_name", "receiver_mobile", "goods_name", "package", "quantity", "weight",
            "volume", "freight", "payment_type",
        )
    }
}
