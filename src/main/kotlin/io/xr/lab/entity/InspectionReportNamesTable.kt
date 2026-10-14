// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionReportNames。
package io.xr.lab.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb

object InspectionReportNamesTable : Table("inspection_report_names") {
  val code = text("code")
  val name = text("name")
  val fullName = text("full_name").nullable()
  val templatePath = text("template_path").nullable()
  val summaryName = text("summary_name").nullable()
  val extFields = jsonb<JsonElement>("ext_fields", Json).nullable()
  val description = text("description").nullable()
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(code)
}
