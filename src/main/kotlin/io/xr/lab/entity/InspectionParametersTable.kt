// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionParameters。
package io.xr.lab.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb

object InspectionParametersTable : Table("inspection_parameters") {
  val code = text("code")
  val name = text("name")
  val rawName = text("raw_name")
  val canonicalName = text("canonical_name")
  val methodText = text("method_text").nullable()
  val aliases = jsonb<JsonElement>("aliases", Json)
  val unit = text("unit").nullable()
  val sourceType = text("source_type")
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(code)
}
