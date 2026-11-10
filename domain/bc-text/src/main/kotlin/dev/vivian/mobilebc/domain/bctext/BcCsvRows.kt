package dev.vivian.mobilebc.domain.bctext

internal fun parseBcCsvRows(csv: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < csv.length) {
        val char = csv[index]
        when {
            char == '"' && inQuotes && index + 1 < csv.length && csv[index + 1] == '"' -> {
                cell.append('"')
                index += 1
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                row += cell.toString()
                cell.clear()
            }
            (char == '\n' || char == '\r') && !inQuotes -> {
                if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index += 1
                row += cell.toString()
                cell.clear()
                if (row.any(String::isNotBlank)) rows += row.toList()
                row.clear()
            }
            else -> cell.append(char)
        }
        index += 1
    }
    row += cell.toString()
    if (row.any(String::isNotBlank)) rows += row.toList()
    return rows
}
