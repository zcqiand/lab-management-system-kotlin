// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionStandards。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionStandardsTable : Table("inspection_standards") {
  val code = text("code")
  val name = text("name")
  val version = text("version").nullable()
  val status = text("status")
  val sourceDocumentId = text("source_document_id").nullable()
  val sourceHash = text("source_hash").nullable()
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(code)
}
