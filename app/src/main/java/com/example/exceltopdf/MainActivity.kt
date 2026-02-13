package com.example.exceltopdf

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.exceltopdf.ui.theme.ExcelToPdfTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import java.io.File
import java.io.FileOutputStream
import com.itextpdf.text.Document
import com.itextpdf.text.PageSize
import com.itextpdf.text.pdf.PdfWriter
import com.itextpdf.text.pdf.PdfPTable
import com.itextpdf.text.Paragraph
import com.itextpdf.text.Font
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Element
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val processingState = mutableStateOf<ProcessingState>(ProcessingState.Idle)
    private var selectedFileUri: Uri? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            processingState.value = ProcessingState.FileSelected(uri.lastPathSegment ?: "Selected file")
            Toast.makeText(this, "File selected", Toast.LENGTH_SHORT).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            ExcelToPdfTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        processingState = processingState.value,
                        onSelectFile = { handleSelectFile() },
                        onConvert = { convertExcelToPdf() }
                    )
                }
            }
        }
    }

    private fun handleSelectFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openFilePicker()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        try {
            filePickerLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun convertExcelToPdf() {
        val uri = selectedFileUri ?: return
        
        lifecycleScope.launch {
            try {
                processingState.value = ProcessingState.Converting(0, 0)
                val result = withContext(Dispatchers.IO) { processExcelFile(uri) }
                processingState.value = ProcessingState.Success(result)
                Toast.makeText(this@MainActivity, "Success! Created ${result.filesCreated} PDFs", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun processExcelFile(uri: Uri): ConversionResult {
        val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Cannot open file")
        val workbook = WorkbookFactory.create(inputStream)
        val totalSheets = workbook.numberOfSheets
        val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ExcelToPdf")
        if (!outputDir.exists()) outputDir.mkdirs()

        val createdFiles = mutableListOf<String>()

        for (i in 0 until totalSheets) {
            withContext(Dispatchers.Main) {
                processingState.value = ProcessingState.Converting(i + 1, totalSheets)
            }

            val sheet = workbook.getSheetAt(i)
            val visitNo = try {
                val row = sheet.getRow(18)
                val cell = row?.getCell(24)
                getCellValueAsString(cell)?.trim()?.takeIf { it.isNotBlank() } ?: "Sheet_${i + 1}"
            } catch (e: Exception) {
                "Sheet_${i + 1}"
            }

            val pdfFile = File(outputDir, "$visitNo.pdf")
            createPdfFromSheet(sheet, pdfFile, visitNo)
            createdFiles.add(pdfFile.name)
        }

        workbook.close()
        inputStream.close()

        return ConversionResult(createdFiles.size, outputDir.absolutePath, createdFiles)
    }

    private fun getCellValueAsString(cell: Cell?): String? {
        if (cell == null) return null
        return try {
            when (cell.cellType) {
                CellType.STRING -> cell.stringCellValue
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        SimpleDateFormat("dd/MM/yyyy", Locale.US).format(cell.dateCellValue)
                    } else {
                        val numValue = cell.numericCellValue
                        if (numValue == numValue.toLong().toDouble()) numValue.toLong().toString() else numValue.toString()
                    }
                }
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                CellType.FORMULA -> try { cell.stringCellValue } catch (e: Exception) { cell.numericCellValue.toString() }
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    private fun createPdfFromSheet(sheet: org.apache.poi.ss.usermodel.Sheet, outputFile: File, visitNo: String) {
        val document = Document(PageSize.A4.rotate())
        val writer = PdfWriter.getInstance(document, FileOutputStream(outputFile))
        document.open()

        val titleFont = Font(Font.FontFamily.HELVETICA, 16f, Font.BOLD, BaseColor.BLACK)
        val title = Paragraph("Visit No: $visitNo", titleFont)
        title.alignment = Element.ALIGN_CENTER
        title.spacingAfter = 20f
        document.add(title)

        val lastRowNum = sheet.lastRowNum
        var maxCols = 0
        for (i in 0..lastRowNum) {
            val row = sheet.getRow(i)
            if (row != null && row.lastCellNum > maxCols) maxCols = row.lastCellNum.toInt()
        }

        if (maxCols > 0 && lastRowNum >= 0) {
            val actualCols = minOf(maxCols, 30)
            val table = PdfPTable(actualCols)
            table.widthPercentage = 100f

            for (rowNum in 0..lastRowNum) {
                val row = sheet.getRow(rowNum)
                if (row != null) {
                    for (colNum in 0 until actualCols) {
                        val cellValue = getCellValueAsString(row.getCell(colNum)) ?: ""
                        val pdfCell = com.itextpdf.text.pdf.PdfPCell(Paragraph(cellValue, Font(Font.FontFamily.HELVETICA, 7f)))
                        pdfCell.setPadding(3f)
                        table.addCell(pdfCell)
                    }
                } else {
                    for (colNum in 0 until actualCols) table.addCell("")
                }
            }
            document.add(table)
        }

        document.close()
        writer.close()
    }
}

@Composable
fun MainScreen(processingState: ProcessingState, onSelectFile: () -> Unit, onConvert: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Excel to PDF Converter", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 32.dp))

        when (processingState) {
            is ProcessingState.Idle -> {
                Text("Select an Excel file to convert", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))
                Button(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) { Text("Select Excel File") }
            }
            is ProcessingState.FileSelected -> {
                Text("Selected: ${processingState.fileName}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
                Button(onClick = onConvert, modifier = Modifier.fillMaxWidth()) { Text("Convert to PDFs") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) { Text("Select Different File") }
            }
            is ProcessingState.Converting -> {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                Text("Converting sheet ${processingState.current} of ${processingState.total}", style = MaterialTheme.typography.bodyLarge)
            }
            is ProcessingState.Success -> {
                Icon(Icons.Default.CheckCircle, "Success", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp).padding(bottom = 16.dp))
                Text("Success!", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                Text("${processingState.result.filesCreated} PDFs created", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                Text("Saved to: ${processingState.result.outputPath}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Created Files:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                        processingState.result.fileNames.forEach { 
                            Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
                Button(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) { Text("Convert Another File") }
            }
            is ProcessingState.Error -> {
                Icon(Icons.Default.Error, "Error", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp).padding(bottom = 16.dp))
                Text("Error", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                Text(processingState.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
                Button(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) { Text("Try Again") }
            }
        }
    }
}

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class FileSelected(val fileName: String) : ProcessingState()
    data class Converting(val current: Int, val total: Int) : ProcessingState()
    data class Success(val result: ConversionResult) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

data class ConversionResult(val filesCreated: Int, val outputPath: String, val fileNames: List<String>)
