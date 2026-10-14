// DB-First 生成物（ADR-0046）：scripts/scaffold-entities.mjs 反推自 lab_dev，禁手改。
// 字段语义见 lab-management-system-shared/src/db/schema.ts inspectionSpecialties。
package io.xr.lab.entity

import org.jetbrains.exposed.v1.core.Table

object InspectionSpecialtiesTable : Table("inspection_specialties") {
  val code = text("code")
  val officialNo = text("official_no")
  val name = text("name")
  val isOfficial = bool("is_official")
  val enabled = bool("enabled")
  val sortOrder = integer("sort_order")
  val createdAt = text("created_at")
  val updatedAt = text("updated_at")
  override val primaryKey = PrimaryKey(code)
}
